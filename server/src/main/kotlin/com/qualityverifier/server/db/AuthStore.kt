package com.qualityverifier.server.db

import com.qualityverifier.domain.Audience
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
    /** One of our own evaluators, not a buyer. Drives the exit questionnaire and the cap. */
    val isTester: Boolean = false,
    /**
     * Which of the two apps this account is for. Fixed at registration by the invite code
     * and never changed — see `V16__account_audience.sql`.
     *
     * Read on every assessment turn, where each endpoint refuses an account belonging to
     * the other app, and on the Fundi Bora profile endpoints.
     */
    val audience: Audience = Audience.BUYER,
    /**
     * A Kagua account on a shop's tablet (V39, row 12). Fixed at registration by the invite
     * code and never changed. The server sends such an account none of its earlier
     * assessments, and its phone deletes each one when the customer is done.
     */
    val shopMode: Boolean = false,
    /**
     * The Fundi Bora account this Kagua shop account belongs to (V43, row 53), or null. Such an
     * account has no number and no password of its own: the Fundi Bora account's password
     * opens it, at sign-in and at the shop owner's password check, and only Fundi Bora changes
     * that password. It counts as [disabled] whenever the Fundi Bora account is disabled or
     * deleted.
     */
    val shopOfUserId: String? = null,
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
    /** That phone already has an account for the invite's app (V38: one per number per
     *  app). Distinct from an unusable invite: the caller needs to know to sign in instead,
     *  and it reveals nothing an attacker could not learn by trying to sign in. */
    data object PhoneTaken : RegisterOutcome
}

/** Enough to check a password and a lockout, and nothing else. */
data class Credentials(
    val userId: String,
    val passwordHash: String?,
    val lockedUntil: Instant?,
    val failedAttempts: Int,
    val disabled: Boolean,
    /**
     * Which app the account is for. A number may hold one account of each (V38), and
     * sign-in uses this to pick the one the signing-in app is for. No default, as audience is
     * never defaulted: a store that forgot to read it would sign everyone in to Kagua.
     */
    val audience: Audience,
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
    /**
     * Every account on [phone]: none, one, or one per app (V38). The sign-in route chooses
     * among them by the app that is signing in; nothing here picks one.
     */
    suspend fun credentialsForPhone(phone: String): List<Credentials>
    suspend fun recordFailedSignIn(userId: String, lockFor: java.time.Duration, threshold: Int): Int
    suspend fun clearFailedSignIns(userId: String)

    /**
     * When the account's sign-in lockout ends, or null when it is not locked. Read by the shop
     * tablet's password check (row 12), which counts its failures as sign-in failures. The
     * default says "not locked", which is what a store that keeps no lockout would say.
     */
    suspend fun lockedUntil(userId: String): Instant? = null

    /**
     * The Kagua shop account linked to the Fundi Bora account [fundiUserId] (V43, row 53),
     * made on first use. Two sign-ins at once get the same account. Null when the linked account
     * exists but is disabled, or when [fundiUserId] is not a live Fundi Bora account. The default
     * says "no shop account", which is what a store with no linked accounts would say.
     */
    suspend fun shopAccountFor(fundiUserId: String): String? = null

    /**
     * The live Kagua shop account linked to [fundiUserId] (V43), without making one: null when
     * there is none. Read when the Fundi Bora password changes, so the shop's tablets sign out
     * with the Fundi Bora account's other devices.
     */
    suspend fun linkedShopAccount(fundiUserId: String): String? = null

    /** The stored hash, for verifying a current password by user rather than by phone. */
    suspend fun passwordHashFor(userId: String): String?

    /** Replaces the hash. The caller has already verified the current password. */
    suspend fun setPasswordHash(userId: String, passwordHash: String)

    /**
     * Closes an account by stripping everything identifying from it, irreversibly.
     *
     * Not an erasure. The assessments stay, keyed on a random uuid that was never derived
     * from anything about the person, because the pilot's whole purpose is judging those
     * assessments and deleting them destroys the record. What goes is the profile: phone,
     * password, name, business name, the location point, and the invite label that often
     * names them. Every device is signed out in the same transaction.
     *
     * It does **not** make the record anonymous, and the app's wording does not claim it
     * does. Photographs show identifiable premises and free text may name a person, and
     * nothing here can reach inside either.
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
        // Both grants come with the code, read in the same transaction that redeems it. A
        // customer cannot ask to be an evaluator, and an evaluator should not have to
        // remember to tick anything at registration. The audience is the same shape of
        // fact: which of the two apps this account is for, decided by whoever handed the
        // code out rather than by the app doing the registering.
        val grants = connection.prepareStatement(REDEEMABLE_INVITE).use { statement ->
            statement.setString(1, registration.inviteCode)
            statement.executeQuery().use {
                if (it.next()) Triple(it.getBoolean(1), it.getString(2), it.getBoolean(3)) else null
            }
        } ?: return@tx RegisterOutcome.InviteUnusable
        // Shop mode (V39, row 12) comes with the code too: only a shop DIL gives a tablet to
        // gets one, and the business question at sign-up does not turn it on.
        val (grantsTester, audienceId, grantsShopMode) = grants

        // No CASE around the point: ST_MakePoint(NULL, NULL) is already NULL, and
        // `? is null` gives Postgres no type to infer, which fails at prepare time
        // rather than at runtime. Typed setNull keeps ST_MakePoint resolvable.
        val sql = """
            insert into users (
                invite_code, display_name, account_type, business_name,
                business_location, business_location_accuracy_m, business_location_at,
                phone, password_hash, password_set_at, is_tester, audience, shop_mode
            ) values (?, ?, ?, ?, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?, ?, now(), ?, ?, ?)
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
                // a Nairobi workshop in the Indian Ocean, with no error at all.
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
                statement.setBoolean(11, grantsTester)
                // Straight through from the code. The CHECK on the column is the backstop
                // if a code is ever given a value this build has not heard of.
                statement.setString(12, audienceId)
                // V39's CHECK refuses shop mode on anything but a Kagua account.
                statement.setBoolean(13, grantsShopMode)
                statement.executeQuery().use { rows ->
                    rows.next()
                    RegisterOutcome.Created(rows.getString(1))
                }
            }
        } catch (e: SQLException) {
            // 23505 = unique violation, and there are now two indexes it could be: the
            // invite was already redeemed, or that phone already has an account for the
            // invite's app. The constraint name is the only way to tell, and telling
            // matters — one means "ask us for a code", the other means "sign in instead".
            when {
                e.sqlState != "23505" -> throw e
                PHONE_INDEXES.any { e.message?.contains(it) == true } -> RegisterOutcome.PhoneTaken
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
            // A linked shop account (V43) is gone when its Fundi Bora account is: that account's
            // password is the only way in, so a refresh or a token must not outlive it.
            connection.prepareStatement(
                """
                select u.id::text, u.display_name, u.account_type, u.business_name,
                       (u.disabled_at is not null or u.deleted_at is not null
                        or o.disabled_at is not null or o.deleted_at is not null) as gone,
                       u.is_tester, u.audience, u.shop_mode, u.shop_of_user_id::text
                from users u left join users o on o.id = u.shop_of_user_id
                where u.id = ?::uuid
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
                        isTester = rows.getBoolean(6),
                        audience = Audience.fromId(rows.getString(7).orEmpty())
                            ?: Audience.BUYER,
                        shopMode = rows.getBoolean(8),
                        shopOfUserId = rows.getString(9),
                    )
                }
            }
        }
    }

    /**
     * Makes the linked shop account if there is none, then reads it, in one transaction.
     *
     * ON CONFLICT on users_shop_of_user_key (V43), so a second sign-in racing the first waits
     * for it and inserts nothing, then reads the row the first one made: READ COMMITTED gives
     * each statement a fresh snapshot. The insert names its audience, as every insert into
     * users does, and copies nothing from the Fundi Bora account: no name, number or password.
     */
    override suspend fun shopAccountFor(fundiUserId: String): String? = tx { connection ->
        connection.prepareStatement(CREATE_SHOP_ACCOUNT).use { statement ->
            statement.setString(1, fundiUserId)
            statement.executeUpdate()
        }
        connection.prepareStatement(LIVE_SHOP_ACCOUNT).use { statement ->
            statement.setString(1, fundiUserId)
            statement.executeQuery().use { rows ->
                if (!rows.next() || rows.getBoolean(2)) null else rows.getString(1)
            }
        }
    }

    override suspend fun linkedShopAccount(fundiUserId: String): String? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(LIVE_SHOP_ACCOUNT).use { statement ->
                statement.setString(1, fundiUserId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
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

    override suspend fun credentialsForPhone(phone: String): List<Credentials> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                // Every account on the number, at most one per audience
                // (users_phone_audience_key, V38). Ordered so the list is the same on
                // every read.
                connection.prepareStatement(
                    """
                    select id::text, password_hash, locked_until, failed_sign_ins,
                           (disabled_at is not null or deleted_at is not null), audience
                    from users where phone = ?
                    order by audience
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, phone)
                    statement.executeQuery().use { rows ->
                        val out = mutableListOf<Credentials>()
                        while (rows.next()) out += Credentials(
                            userId = rows.getString(1),
                            passwordHash = rows.getString(2),
                            lockedUntil = rows.getTimestamp(3)?.toInstant(),
                            failedAttempts = rows.getInt(4),
                            disabled = rows.getBoolean(5),
                            // The CHECK on the column (V16) holds it to the two ids.
                            audience = Audience.fromId(rows.getString(6).orEmpty())
                                ?: Audience.BUYER,
                        )
                        out
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

    override suspend fun lockedUntil(userId: String): Instant? = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select locked_until from users where id = ?::uuid and locked_until > now()"
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getTimestamp(1)?.toInstant() else null
                }
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
        // anonymise_user does the work, and does it in SQL rather than here on purpose:
        // which columns count as identifying is a policy statement, and one edited in a
        // migration can be reviewed in a diff. It also marks deleted_at and drops every
        // refresh token, so there is nothing to do afterwards.
        //
        // Irreversible and immediate. There is no grace period to undo it in, which is
        // the price of the promise being unambiguous — see docs/reverting-to-full-deletion.md
        // for the reasoning and for how to go back to erasure.
        connection.prepareStatement("select anonymise_user(?::uuid)").use { statement ->
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

    internal companion object {
        /**
         * The grants of a code that can still register an account. No row for an unknown
         * or revoked code, and register() answers no row with InviteUnusable.
         *
         * Revoked includes the code of a deleted account: anonymise_user revokes it (V26)
         * before blanking users.invite_code, whose unique index is otherwise the only thing
         * keeping a code single use. A constant so AuthTest can hold it to that.
         *
         * FOR SHARE closes the window while a delete is in flight: without it, a sign-up
         * that read the code before anonymise_user committed would find the unique index
         * free once it did. The lock makes the read wait and re-check revoked_at.
         */
        const val REDEEMABLE_INVITE =
            "select grants_tester, audience, grants_shop_mode from invite_codes where code = ? and revoked_at is null for share"

        /**
         * The unique index that refuses a second account on one number for one app, by name,
         * since a unique violation says only which index it hit. V38 replaced V5's
         * users_phone_key (one per number) with users_phone_audience_key (one per number per
         * app). Both are named so the jar installed straight after the migration, and a
         * database the migration has not yet reached, answer phone_taken alike. MigrationsTest
         * holds the first to the name V38 creates.
         */
        val PHONE_INDEXES = listOf("users_phone_audience_key", "users_phone_key")

        /**
         * The Kagua shop account of a Fundi Bora account (V43, row 53), made only for a live
         * Fundi Bora account and only if it has none. V43's self-test runs the same statement;
         * MigrationsTest holds the two together.
         */
        const val CREATE_SHOP_ACCOUNT =
            "insert into users (audience, shop_mode, shop_of_user_id) " +
                "select 'buyer', true, f.id from users f " +
                "where f.id = ?::uuid and f.audience = 'fundi' and f.deleted_at is null and f.disabled_at is null " +
                "on conflict (shop_of_user_id) where shop_of_user_id is not null and deleted_at is null do nothing"

        /** The live shop account of a Fundi Bora account, and whether it is disabled. At most one row (V43's index). */
        private const val LIVE_SHOP_ACCOUNT =
            "select id::text, (disabled_at is not null) from users where shop_of_user_id = ?::uuid and deleted_at is null"
    }
}
