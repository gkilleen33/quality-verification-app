package com.qualityverifier.server.db

import com.qualityverifier.server.auth.Tokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.time.Instant
import javax.sql.DataSource

/** A registered user, as much of one as anything outside registration needs. */
data class UserRow(
    val id: String,
    val displayName: String?,
    val accountType: String?,
    val businessName: String?,
    val disabled: Boolean,
)

/** What registration was given. Validated before it reaches here. */
data class Registration(
    val inviteCode: String,
    val phone: String,
    /** Already Argon2id-encoded. The plaintext never reaches this layer. */
    val passwordHash: String,
    val displayName: String,
    val accountType: String,
    val businessName: String?,
    val latitude: Double?,
    val longitude: Double?,
    val accuracyMetres: Double?,
)

sealed interface RegisterOutcome {
    data class Created(val userId: String) : RegisterOutcome
    /** Unknown, revoked, or already redeemed — one answer for all three, on purpose. */
    data object InviteUnusable : RegisterOutcome
    /** That phone already has an account. Distinct from an unusable invite: the caller
     *  needs to know to sign in instead, and it reveals nothing an attacker could not
     *  learn by trying to sign in. */
    data object PhoneTaken : RegisterOutcome
}

/** Enough to check a password and a lockout, and nothing else. */
data class Credentials(
    val userId: String,
    val passwordHash: String?,
    val lockedUntil: Instant?,
    val failedAttempts: Int,
    val disabled: Boolean,
)

data class StoredRefresh(
    val id: String,
    val userId: String,
    val expiresAt: Instant,
    val spent: Boolean,
    val revoked: Boolean,
)

/**
 * What the auth routes need from storage.
 *
 * An interface because the decisions worth testing are in the routes, not the SQL:
 * whether a replayed refresh token revokes the chain, whether a disabled user can
 * still refresh. Those are hard to provoke with curl against a real database and easy
 * to state with a fake. The SQL itself is verified against the real Postgres.
 */
interface AuthStore {
    suspend fun register(registration: Registration): RegisterOutcome
    suspend fun findUser(userId: String): UserRow?
    suspend fun issueRefresh(
        userId: String,
        token: String,
        expiresAt: Instant,
        userAgent: String?,
        replaces: String? = null,
    ): String
    suspend fun findRefresh(token: String): StoredRefresh?
    suspend fun revokeChain(userId: String): Int
    suspend fun credentialsForPhone(phone: String): Credentials?
    suspend fun recordFailedSignIn(userId: String, lockFor: java.time.Duration, threshold: Int): Int
    suspend fun clearFailedSignIns(userId: String)

    /** The stored hash, for verifying a current password by user rather than by phone. */
    suspend fun passwordHashFor(userId: String): String?

    /** Replaces the hash. The caller has already verified the current password. */
    suspend fun setPasswordHash(userId: String, passwordHash: String)

    /**
     * Marks the account deleted. Everything of theirs goes at the retention window; the
     * flag is what starts the clock, and revoking their tokens is what makes it stick.
     */
    suspend fun markAccountDeleted(userId: String)
}

/**
 * Plain JDBC over the Hikari pool. No ORM: this is a handful of statements, and a
 * mapping layer would be more code than the code it maps.
 */
class PostgresAuthStore(private val dataSource: DataSource) : AuthStore {

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    /**
     * Redeems an invite and creates the user in one transaction.
     *
     * Single use is enforced by the unique index on users.invite_code rather than by
     * checking first and inserting after — two phones redeeming the same code at the
     * same moment would both pass that check. The constraint cannot be raced.
     */
    override suspend fun register(registration: Registration): RegisterOutcome = tx { connection ->
        val usable = connection.prepareStatement(
            "select 1 from invite_codes where code = ? and revoked_at is null"
        ).use { statement ->
            statement.setString(1, registration.inviteCode)
            statement.executeQuery().use { it.next() }
        }
        if (!usable) return@tx RegisterOutcome.InviteUnusable

        // No CASE around the point: ST_MakePoint(NULL, NULL) is already NULL, and
        // `? is null` gives Postgres no type to infer, which fails at prepare time
        // rather than at runtime. Typed setNull keeps ST_MakePoint resolvable.
        val sql = """
            insert into users (
                invite_code, display_name, account_type, business_name,
                business_location, business_location_accuracy_m, business_location_at,
                phone, password_hash, password_set_at
            ) values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?, now())
            returning id::text
        """.trimIndent()

        // The users_location_is_complete constraint says accuracy and timestamp only
        // exist alongside a point. Enforced here too, so a client sending an accuracy
        // with no fix gets a user row rather than a constraint violation.
        val hasPoint = registration.latitude != null && registration.longitude != null

        try {
            connection.prepareStatement(sql).use { statement ->
                statement.setString(1, registration.inviteCode)
                statement.setString(2, registration.displayName)
                statement.setString(3, registration.accountType)
                statement.setString(4, registration.businessName)
                // Longitude first: ST_MakePoint takes x then y. The wrong way round puts
                // a Kampala workshop in the Indian Ocean, with no error at all.
                setNullableDouble(statement, 5, registration.longitude.takeIf { hasPoint })
                setNullableDouble(statement, 6, registration.latitude.takeIf { hasPoint })
                setNullableDouble(statement, 7, registration.accuracyMetres.takeIf { hasPoint })
                if (hasPoint) {
                    statement.setTimestamp(8, java.sql.Timestamp.from(java.time.Instant.now()))
                } else {
                    statement.setNull(8, java.sql.Types.TIMESTAMP)
                }
                statement.setString(9, registration.phone)
                statement.setString(10, registration.passwordHash)
                statement.executeQuery().use { rows ->
                    rows.next()
                    RegisterOutcome.Created(rows.getString(1))
                }
            }
        } catch (e: SQLException) {
            // 23505 = unique violation, and there are now two indexes it could be: the
            // invite was already redeemed, or that phone already has an account. The
            // constraint name is the only way to tell, and telling matters — one means
            // "ask us for a code", the other means "sign in instead".
            when {
                e.sqlState != "23505" -> throw e
                e.message?.contains("users_phone_key") == true -> RegisterOutcome.PhoneTaken
                else -> RegisterOutcome.InviteUnusable
            }
        }
    }

    private fun setNullableDouble(
        statement: java.sql.PreparedStatement,
        index: Int,
        value: Double?,
    ) {
        if (value == null) statement.setNull(index, java.sql.Types.DOUBLE)
        else statement.setDouble(index, value)
    }

    override suspend fun findUser(userId: String): UserRow? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select id::text, display_name, account_type, business_name,
                       (disabled_at is not null or deleted_at is not null) as gone
                from users where id = ?::uuid
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else UserRow(
                        id = rows.getString(1),
                        displayName = rows.getString(2),
                        accountType = rows.getString(3),
                        businessName = rows.getString(4),
                        disabled = rows.getBoolean(5),
                    )
                }
            }
        }
    }

    override suspend fun issueRefresh(
        userId: String,
        token: String,
        expiresAt: Instant,
        userAgent: String?,
        replaces: String?,
    ): String = tx { connection ->
        val id = connection.prepareStatement(
            """
            insert into refresh_tokens (user_id, token_hash, expires_at, user_agent)
            values (?::uuid, ?, ?, ?) returning id::text
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, Tokens.hash(token))
            statement.setTimestamp(3, java.sql.Timestamp.from(expiresAt))
            statement.setString(4, userAgent)
            statement.executeQuery().use { rows -> rows.next(); rows.getString(1) }
        }
        if (replaces != null) {
            connection.prepareStatement(
                "update refresh_tokens set used_at = now(), replaced_by = ?::uuid where id = ?::uuid"
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, replaces)
                statement.executeUpdate()
            }
        }
        id
    }

    override suspend fun findRefresh(token: String): StoredRefresh? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select id::text, user_id::text, expires_at,
                       used_at is not null, revoked_at is not null
                from refresh_tokens where token_hash = ?
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, Tokens.hash(token))
                statement.executeQuery().use { rows ->
                    if (!rows.next()) null else StoredRefresh(
                        id = rows.getString(1),
                        userId = rows.getString(2),
                        expiresAt = rows.getTimestamp(3).toInstant(),
                        spent = rows.getBoolean(4),
                        revoked = rows.getBoolean(5),
                    )
                }
            }
        }
    }

    override suspend fun credentialsForPhone(phone: String): Credentials? =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select id::text, password_hash, locked_until, failed_sign_ins,
                           (disabled_at is not null or deleted_at is not null)
                    from users where phone = ?
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, phone)
                    statement.executeQuery().use { rows ->
                        if (!rows.next()) null else Credentials(
                            userId = rows.getString(1),
                            passwordHash = rows.getString(2),
                            lockedUntil = rows.getTimestamp(3)?.toInstant(),
                            failedAttempts = rows.getInt(4),
                            disabled = rows.getBoolean(5),
                        )
                    }
                }
            }
        }

    /**
     * Counts a failed attempt and locks the account once the threshold is reached.
     *
     * The increment and the lock decision are one statement so concurrent attempts
     * cannot both read "9 failures" and both decide not to lock.
     */
    override suspend fun recordFailedSignIn(
        userId: String,
        lockFor: java.time.Duration,
        threshold: Int,
    ): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                update users
                set failed_sign_ins = failed_sign_ins + 1,
                    locked_until = case when failed_sign_ins + 1 >= ?
                                        then now() + (? || ' seconds')::interval
                                        else locked_until end
                where id = ?::uuid
                returning failed_sign_ins
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, threshold)
                statement.setString(2, lockFor.seconds.toString())
                statement.setString(3, userId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }
    }

    override suspend fun clearFailedSignIns(userId: String) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "update users set failed_sign_ins = 0, locked_until = null where id = ?::uuid"
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeUpdate()
            }
            Unit
        }
    }

    override suspend fun passwordHashFor(userId: String): String? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select password_hash from users where id = ?::uuid and deleted_at is null"
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) else null
                }
            }
        }
    }

    override suspend fun setPasswordHash(userId: String, passwordHash: String) =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    update users set password_hash = ?, password_set_at = now(),
                                     failed_sign_ins = 0, locked_until = null
                    where id = ?::uuid
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, passwordHash)
                    statement.setString(2, userId)
                    statement.executeUpdate()
                }
                Unit
            }
        }

    override suspend fun markAccountDeleted(userId: String) = tx { connection ->
        connection.prepareStatement(
            "update users set deleted_at = now() where id = ?::uuid and deleted_at is null"
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeUpdate()
        }
        // In the same transaction: an account marked deleted whose tokens still work is
        // an account that is not deleted.
        connection.prepareStatement("select revoke_refresh_chain(?::uuid)").use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { it.next() }
        }
        Unit
    }

    /** Everything live for this user. Called when a spent token comes back. */
    override suspend fun revokeChain(userId: String): Int = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select revoke_refresh_chain(?::uuid)").use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows -> rows.next(); rows.getInt(1) }
            }
        }
    }
}
