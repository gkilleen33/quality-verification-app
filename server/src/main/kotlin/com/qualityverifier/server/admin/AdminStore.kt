package com.qualityverifier.server.admin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.time.Duration
import java.time.Instant
import java.util.UUID
import javax.sql.DataSource

/** An admin as the login flow needs them. */
data class AdminCredentials(
    val id: String,
    val email: String,
    val name: String,
    val passwordHash: String,
    val totpSecret: String?,
    val totpConfirmed: Boolean,
    val lockedUntil: Instant?,
    val disabled: Boolean,
)

data class AdminRow(
    val id: String,
    val email: String,
    val name: String,
    val createdAt: Instant,
    val createdByEmail: String?,
    val lastSignInAt: Instant?,
    val disabled: Boolean,
    val twoFactorReady: Boolean,
)

data class InviteRow(
    val code: String,
    val label: String?,
    val createdAt: Instant,
    val revokedAt: Instant?,
    val timesUsed: Int,
)

data class UserRow(
    val id: String,
    val phone: String,
    val name: String?,
    val accountType: String?,
    val businessName: String?,
    val createdAt: Instant,
    val assessments: Int,
    val deleted: Boolean,
)

data class AuditRow(
    val adminEmail: String,
    val action: String,
    val target: String?,
    val detail: String?,
    val ip: String?,
    val createdAt: Instant,
)


data class AdminSessionRow(
    val id: String,
    val itemTypeId: String,
    val userPhone: String?,
    val userName: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val messageCount: Int,
    val verdictLevelId: String?,
    val photoCount: Int,
    val clientDeleted: Boolean,
)

data class AdminMessageRow(
    val role: String,
    val text: String,
    val createdAt: Instant,
    val photoHashes: List<String>,
)

/** One page of results plus whether there is another. */
data class Page<T>(val items: List<T>, val hasMore: Boolean)

/**
 * Everything the portal reads and writes.
 *
 * An interface for the same reason AuthStore and ChatStore are: the decisions worth testing
 * are in the routes, and a portal whose security rules can only be exercised against a live
 * database is a portal whose security rules are not exercised.
 *
 * Separate from those two rather than bolted onto them: they serve the phone, and the
 * queries here are wider than anything a customer is ever allowed to run. Keeping them
 * apart means a mistake in a portal query cannot widen a customer endpoint.
 *
 * Every read is paginated with an explicit limit. Not for performance — for the box. A
 * portal query that streamed every session on a 3.7 GB instance shared with Postgres would
 * take the phone API down with it.
 */
interface AdminStore {
    suspend fun overview(): Overview
    suspend fun credentialsFor(email: String): AdminCredentials?
    /** Returns the new id and its TOTP secret, or null when the email is taken. */
    suspend fun createAdmin(email: String, name: String, passwordHash: String, createdBy: String?): Pair<String, String>?
    suspend fun confirmTotp(adminId: String)
    suspend fun recordSignIn(adminId: String)
    suspend fun recordFailure(adminId: String, lockFor: Duration, threshold: Int): Int
    suspend fun setPasswordHash(adminId: String, hash: String)
    suspend fun setDisabled(adminId: String, disabled: Boolean)
    suspend fun admins(): List<AdminRow>
    /** How many admins can still sign in. Used to refuse disabling the last one. */
    suspend fun activeAdminCount(): Int
    suspend fun invites(): List<InviteRow>
    suspend fun createInvite(code: String, label: String?): Boolean
    suspend fun revokeInvite(code: String): Boolean
    suspend fun users(limit: Int, offset: Int, search: String?): Page<UserRow>
    suspend fun sessions(limit: Int, offset: Int, userId: String?, itemTypeId: String?): Page<AdminSessionRow>
    suspend fun sessionHeader(sessionId: String): AdminSessionRow?
    suspend fun conversation(sessionId: String): List<AdminMessageRow>
    suspend fun blobExists(sha: String): Boolean
    suspend fun audit(
        adminId: String?,
        adminEmail: String,
        action: String,
        target: String? = null,
        detail: String? = null,
        ip: String? = null,
    )
    suspend fun auditTrail(limit: Int, offset: Int): Page<AuditRow>
}

class PostgresAdminStore(private val dataSource: DataSource) : AdminStore {

    private suspend fun <T> query(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use(block)
    }


    /**
     * The five numbers on the front page, in one round trip.
     *
     * A single query rather than five: they are all counts of small tables, and five
     * connections from the pool for one page view is five the phone API cannot have.
     */
    override suspend fun overview(): Overview = query { connection ->
        val counts = connection.prepareStatement(
            """
            select
              (select count(*)::int from users where deleted_at is null),
              (select count(*)::int from sessions),
              (select count(*)::int from sessions
                where (created_at at time zone 'Africa/Kampala')::date
                    = (now() at time zone 'Africa/Kampala')::date),
              (select count(distinct sha256)::int from attachments),
              (select count(*)::int from invite_codes i
                where i.revoked_at is null
                  and not exists (select 1 from users u where u.invite_code = i.code))
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                rows.next()
                listOf(rows.getInt(1), rows.getInt(2), rows.getInt(3), rows.getInt(4), rows.getInt(5))
            }
        }
        Overview(
            users = counts[0],
            sessions = counts[1],
            sessionsToday = counts[2],
            photos = counts[3],
            unusedInvites = counts[4],
            recentAudit = emptyList(),
        )
    }

    // ------------------------------------------------------------------ accounts

    override suspend fun credentialsFor(email: String): AdminCredentials? = query { connection ->
        connection.prepareStatement(
            """
            select id::text, email, name, password_hash, totp_secret,
                   (totp_confirmed_at is not null), locked_until, (disabled_at is not null)
            from admins where lower(email) = lower(?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, email)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else AdminCredentials(
                    id = rows.getString(1),
                    email = rows.getString(2),
                    name = rows.getString(3),
                    passwordHash = rows.getString(4),
                    totpSecret = rows.getString(5),
                    totpConfirmed = rows.getBoolean(6),
                    lockedUntil = rows.getTimestamp(7)?.toInstant(),
                    disabled = rows.getBoolean(8),
                )
            }
        }
    }

    /**
     * Creates an admin. Returns null when the email is taken.
     *
     * The TOTP secret is generated here and stored unconfirmed: the account cannot sign in
     * until whoever holds it has scanned the code and proved they can produce one.
     */
    override suspend fun createAdmin(
        email: String,
        name: String,
        passwordHash: String,
        createdBy: String?,
    ): Pair<String, String>? = query { connection ->
        val id = UUID.randomUUID().toString()
        val secret = Totp.newSecret()
        val inserted = connection.prepareStatement(
            """
            insert into admins (id, email, name, password_hash, totp_secret, created_by)
            values (?::uuid, ?, ?, ?, ?, ?::uuid)
            on conflict do nothing
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.setString(2, email)
            statement.setString(3, name)
            statement.setString(4, passwordHash)
            statement.setString(5, secret)
            if (createdBy == null) statement.setNull(6, java.sql.Types.OTHER)
            else statement.setString(6, createdBy)
            statement.executeUpdate()
        }
        if (inserted == 0) null else id to secret
    }

    /** Marks the second factor proved, which is what makes the account usable. */
    override suspend fun confirmTotp(adminId: String) = query { connection ->
        connection.prepareStatement(
            "update admins set totp_confirmed_at = now() where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, adminId)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun recordSignIn(adminId: String) = query { connection ->
        connection.prepareStatement(
            """
            update admins set last_sign_in_at = now(), failed_sign_ins = 0, locked_until = null
            where id = ?::uuid
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, adminId)
            statement.executeUpdate()
        }
        Unit
    }

    /**
     * Counts a failure and locks at the threshold, in one statement.
     *
     * Same shape as the customer path, and for the same reason: two concurrent attempts
     * must not both read "4 failures" and both decide not to lock.
     */
    override suspend fun recordFailure(adminId: String, lockFor: Duration, threshold: Int): Int =
        query { connection ->
            connection.prepareStatement(
                """
                update admins
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
                statement.setString(3, adminId)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
            }
        }

    override suspend fun setPasswordHash(adminId: String, hash: String) = query { connection ->
        connection.prepareStatement(
            "update admins set password_hash = ? where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, hash)
            statement.setString(2, adminId)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun setDisabled(adminId: String, disabled: Boolean) = query { connection ->
        connection.prepareStatement(
            "update admins set disabled_at = ${if (disabled) "now()" else "null"} where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, adminId)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun admins(): List<AdminRow> = query { connection ->
        connection.prepareStatement(
            """
            select a.id::text, a.email, a.name, a.created_at, c.email,
                   a.last_sign_in_at, (a.disabled_at is not null),
                   (a.totp_confirmed_at is not null)
            from admins a left join admins c on c.id = a.created_by
            order by a.created_at
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val out = mutableListOf<AdminRow>()
                while (rows.next()) out += AdminRow(
                    id = rows.getString(1),
                    email = rows.getString(2),
                    name = rows.getString(3),
                    createdAt = rows.getTimestamp(4).toInstant(),
                    createdByEmail = rows.getString(5),
                    lastSignInAt = rows.getTimestamp(6)?.toInstant(),
                    disabled = rows.getBoolean(7),
                    twoFactorReady = rows.getBoolean(8),
                )
                out
            }
        }
    }

    /** How many admins can still sign in. Used to refuse disabling the last one. */
    override suspend fun activeAdminCount(): Int = query { connection ->
        connection.prepareStatement(
            "select count(*)::int from admins where disabled_at is null"
        ).use { statement ->
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
    }

    // ------------------------------------------------------------------ invites

    override suspend fun invites(): List<InviteRow> = query { connection ->
        connection.prepareStatement(
            """
            select i.code, i.label, i.created_at, i.revoked_at,
                   (select count(*)::int from users u where u.invite_code = i.code)
            from invite_codes i
            order by i.created_at desc
            """.trimIndent()
        ).use { statement ->
            statement.executeQuery().use { rows ->
                val out = mutableListOf<InviteRow>()
                while (rows.next()) out += InviteRow(
                    code = rows.getString(1),
                    label = rows.getString(2),
                    createdAt = rows.getTimestamp(3).toInstant(),
                    revokedAt = rows.getTimestamp(4)?.toInstant(),
                    timesUsed = rows.getInt(5),
                )
                out
            }
        }
    }

    override suspend fun createInvite(code: String, label: String?): Boolean = query { connection ->
        connection.prepareStatement(
            "insert into invite_codes (code, label) values (?, ?) on conflict do nothing"
        ).use { statement ->
            statement.setString(1, code)
            statement.setString(2, label)
            statement.executeUpdate() > 0
        }
    }

    override suspend fun revokeInvite(code: String): Boolean = query { connection ->
        connection.prepareStatement(
            "update invite_codes set revoked_at = now() where code = ? and revoked_at is null"
        ).use { statement ->
            statement.setString(1, code)
            statement.executeUpdate() > 0
        }
    }

    // ------------------------------------------------------------------ curated reads

    override suspend fun users(limit: Int, offset: Int, search: String?): Page<UserRow> = query { connection ->
        // Parameterised LIKE rather than string building. This is a text box on a page that
        // can read every conversation in the system; it is the last place to hand-roll SQL.
        connection.prepareStatement(
            """
            select u.id::text, u.phone, u.display_name, u.account_type, u.business_name, u.created_at,
                   (select count(*)::int from sessions s where s.user_id = u.id),
                   (u.deleted_at is not null)
            from users u
            where (?::text is null
                   or u.phone ilike '%' || ? || '%'
                   or u.display_name ilike '%' || ? || '%'
                   or u.business_name ilike '%' || ? || '%')
            order by u.created_at desc
            limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, search)
            statement.setString(2, search ?: "")
            statement.setString(3, search ?: "")
            statement.setString(4, search ?: "")
            // One more than asked for, so "is there a next page" needs no count query.
            statement.setInt(5, limit + 1)
            statement.setInt(6, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<UserRow>()
                while (rows.next()) out += UserRow(
                    id = rows.getString(1),
                    phone = rows.getString(2),
                    name = rows.getString(3),
                    accountType = rows.getString(4),
                    businessName = rows.getString(5),
                    createdAt = rows.getTimestamp(6).toInstant(),
                    assessments = rows.getInt(7),
                    deleted = rows.getBoolean(8),
                )
                Page(out.take(limit), hasMore = out.size > limit)
            }
        }
    }


    // ------------------------------------------------------------------ assessments

    /**
     * Assessments across every account, newest first.
     *
     * Unscoped by user, which is the whole reason this lives here rather than on ChatStore:
     * that interface serves the phone, and a query without a user_id has no business being
     * one call-site away from a customer endpoint.
     */
    override suspend fun sessions(
        limit: Int,
        offset: Int,
        userId: String?,
        itemTypeId: String?,
    ): Page<AdminSessionRow> = query { connection ->
        connection.prepareStatement(
            """
            select s.id::text, s.item_type_id, u.phone, u.display_name,
                   s.created_at, s.updated_at,
                   (select count(*)::int from messages m where m.session_id = s.id),
                   s.verdict_level_id,
                   (select count(*)::int from attachments a
                      join messages m2 on m2.id = a.message_id
                     where m2.session_id = s.id),
                   (s.client_deleted_at is not null)
            from sessions s left join users u on u.id = s.user_id
            where (?::uuid is null or s.user_id = ?::uuid)
              and (?::text is null or s.item_type_id = ?)
            order by s.created_at desc
            limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, userId)
            statement.setString(3, itemTypeId)
            statement.setString(4, itemTypeId)
            statement.setInt(5, limit + 1)
            statement.setInt(6, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<AdminSessionRow>()
                while (rows.next()) out += AdminSessionRow(
                    id = rows.getString(1),
                    itemTypeId = rows.getString(2),
                    userPhone = rows.getString(3),
                    userName = rows.getString(4),
                    createdAt = rows.getTimestamp(5).toInstant(),
                    updatedAt = rows.getTimestamp(6).toInstant(),
                    messageCount = rows.getInt(7),
                    verdictLevelId = rows.getString(8),
                    photoCount = rows.getInt(9),
                    clientDeleted = rows.getBoolean(10),
                )
                Page(out.take(limit), hasMore = out.size > limit)
            }
        }
    }

    override suspend fun sessionHeader(sessionId: String): AdminSessionRow? = query { connection ->
        connection.prepareStatement(
            """
            select s.id::text, s.item_type_id, u.phone, u.display_name,
                   s.created_at, s.updated_at,
                   (select count(*)::int from messages m where m.session_id = s.id),
                   s.verdict_level_id, 0, (s.client_deleted_at is not null)
            from sessions s left join users u on u.id = s.user_id
            where s.id = ?::uuid
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else AdminSessionRow(
                    id = rows.getString(1),
                    itemTypeId = rows.getString(2),
                    userPhone = rows.getString(3),
                    userName = rows.getString(4),
                    createdAt = rows.getTimestamp(5).toInstant(),
                    updatedAt = rows.getTimestamp(6).toInstant(),
                    messageCount = rows.getInt(7),
                    verdictLevelId = rows.getString(8),
                    photoCount = rows.getInt(9),
                    clientDeleted = rows.getBoolean(10),
                )
            }
        }
    }

    /** Every turn of one conversation, in order, with its photo hashes. */
    override suspend fun conversation(sessionId: String): List<AdminMessageRow> = query { connection ->
        val messages = connection.prepareStatement(
            """
            select id::text, role, text, created_at
            from messages where session_id = ?::uuid order by ordinal
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<Triple<String, AdminMessageRow, Unit>>()
                while (rows.next()) out += Triple(
                    rows.getString(1),
                    AdminMessageRow(
                        role = rows.getString(2),
                        text = rows.getString(3) ?: "",
                        createdAt = rows.getTimestamp(4).toInstant(),
                        photoHashes = emptyList(),
                    ),
                    Unit,
                )
                out
            }
        }
        if (messages.isEmpty()) return@query emptyList()

        // One query for every attachment in the conversation rather than one per turn.
        // Nine photos across four turns is five round trips either way on a fast local
        // socket, but the shape stops mattering the moment somebody opens a long session.
        val byMessage = connection.prepareStatement(
            """
            select a.message_id::text, a.sha256
            from attachments a join messages m on m.id = a.message_id
            where m.session_id = ?::uuid
            order by a.message_id, a.ordinal, a.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sessionId)
            statement.executeQuery().use { rows ->
                val map = mutableMapOf<String, MutableList<String>>()
                while (rows.next()) {
                    map.getOrPut(rows.getString(1)) { mutableListOf() } += rows.getString(2)
                }
                map
            }
        }
        messages.map { (id, message, _) ->
            message.copy(photoHashes = byMessage[id].orEmpty())
        }
    }

    /** Whether this hash appears anywhere at all. The portal is not scoped to one user. */
    override suspend fun blobExists(sha: String): Boolean = query { connection ->
        connection.prepareStatement(
            "select exists (select 1 from attachments where sha256 = ?)"
        ).use { statement ->
            statement.setString(1, sha)
            statement.executeQuery().use { rows -> rows.next() && rows.getBoolean(1) }
        }
    }

    // ------------------------------------------------------------------ audit


    /**
     * Writes an audit entry.
     *
     * Takes the email as well as the id so the trail still names who acted after that
     * admin's row is gone.
     */
    override suspend fun audit(
        adminId: String?,
        adminEmail: String,
        action: String,
        target: String?,
        detail: String?,
        ip: String?,
    ) = query { connection ->
        connection.prepareStatement(
            """
            insert into admin_audit (admin_id, admin_email, action, target, detail, ip)
            values (?::uuid, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            if (adminId == null) statement.setNull(1, java.sql.Types.OTHER)
            else statement.setString(1, adminId)
            statement.setString(2, adminEmail)
            statement.setString(3, action)
            statement.setString(4, target)
            statement.setString(5, detail)
            statement.setString(6, ip)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun auditTrail(limit: Int, offset: Int): Page<AuditRow> = query { connection ->
        connection.prepareStatement(
            """
            select admin_email, action, target, detail, ip, created_at
            from admin_audit order by created_at desc limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit + 1)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<AuditRow>()
                while (rows.next()) out += AuditRow(
                    adminEmail = rows.getString(1),
                    action = rows.getString(2),
                    target = rows.getString(3),
                    detail = rows.getString(4),
                    ip = rows.getString(5),
                    createdAt = rows.getTimestamp(6).toInstant(),
                )
                Page(out.take(limit), hasMore = out.size > limit)
            }
        }
    }
}
