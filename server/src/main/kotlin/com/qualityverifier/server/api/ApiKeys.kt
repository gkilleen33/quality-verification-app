package com.qualityverifier.server.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import javax.sql.DataSource

/** A key as the portal lists it. Never the key itself, which exists once and is not stored. */
data class ApiKeyRow(
    val id: String,
    val label: String,
    /** Enough to recognise it by, not enough to use. */
    val prefix: String,
    val createdAt: Instant,
    val createdByEmail: String?,
    val lastUsedAt: Instant?,
    val revokedAt: Instant?,
)

/** A newly minted key. [secret] is shown once and then only its hash survives. */
data class NewApiKey(val id: String, val secret: String, val prefix: String)

/**
 * Credentials for the read-only data API.
 *
 * The API exposes identifiers and photographs, so a key here reads the whole corpus with
 * one string — a higher-value credential than an admin password, which needs a second
 * factor and leaves an audit row per page. Hence: hashed at rest, shown once, revocable,
 * and audited on every request.
 */
interface ApiKeyStore {
    suspend fun create(label: String, createdBy: String?): NewApiKey

    /**
     * The id of the live key with this secret, or null.
     *
     * Returns the id rather than a boolean so the caller can record *which* key made a
     * request. With a full-corpus credential, "somebody read everything" is a much less
     * useful audit line than "this key did".
     */
    suspend fun idFor(secret: String): String?

    suspend fun markUsed(id: String)

    suspend fun keys(): List<ApiKeyRow>

    suspend fun revoke(id: String): Boolean
}

class PostgresApiKeyStore(private val dataSource: DataSource) : ApiKeyStore {

    override suspend fun create(label: String, createdBy: String?): NewApiKey =
        withContext(Dispatchers.IO) {
            val secret = newSecret()
            val id = dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    insert into api_keys (label, key_hash, key_prefix, created_by)
                    values (?, ?, ?, ?::uuid)
                    returning id::text
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, label)
                    statement.setString(2, hash(secret))
                    statement.setString(3, prefixOf(secret))
                    statement.setString(4, createdBy)
                    statement.executeQuery().use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                }
            }
            NewApiKey(id, secret, prefixOf(secret))
        }

    override suspend fun idFor(secret: String): String? = withContext(Dispatchers.IO) {
        // Looked up by hash, so a database read yields nothing usable. Revoked keys are
        // excluded in the query rather than checked afterwards: forgetting that second
        // check is how a revoked credential keeps working.
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "select id::text from api_keys where key_hash = ? and revoked_at is null"
            ).use { statement ->
                statement.setString(1, hash(secret))
                statement.executeQuery().use { if (it.next()) it.getString(1) else null }
            }
        }
    }

    override suspend fun markUsed(id: String) {
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "update api_keys set last_used_at = now() where id = ?::uuid"
                ).use { statement ->
                    statement.setString(1, id)
                    statement.executeUpdate()
                }
            }
        }
    }

    override suspend fun keys(): List<ApiKeyRow> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                select k.id::text, k.label, k.key_prefix, k.created_at,
                       a.email, k.last_used_at, k.revoked_at
                  from api_keys k left join admins a on a.id = k.created_by
                 order by k.created_at desc
                """.trimIndent()
            ).use { statement ->
                statement.executeQuery().use { rows ->
                    val out = mutableListOf<ApiKeyRow>()
                    while (rows.next()) out += ApiKeyRow(
                        id = rows.getString(1),
                        label = rows.getString(2),
                        prefix = rows.getString(3),
                        createdAt = rows.getTimestamp(4).toInstant(),
                        createdByEmail = rows.getString(5),
                        lastUsedAt = rows.getTimestamp(6)?.toInstant(),
                        revokedAt = rows.getTimestamp(7)?.toInstant(),
                    )
                    out
                }
            }
        }
    }

    override suspend fun revoke(id: String): Boolean = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "update api_keys set revoked_at = now() where id = ?::uuid and revoked_at is null"
            ).use { statement ->
                statement.setString(1, id)
                statement.executeUpdate() > 0
            }
        }
    }

    companion object {
        private val random = SecureRandom()

        /**
         * A recognisable prefix and 32 random bytes.
         *
         * The prefix means a key found in a config file, a notebook or a log is obviously a
         * Kagua credential and gets reported rather than ignored, and gives secret scanners
         * something to match.
         */
        fun newSecret(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return "kagua_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }

        fun hash(secret: String): String =
            MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())
                .joinToString("") { "%02x".format(it) }

        /** Enough to tell two keys apart in a list, far too little to guess the rest. */
        fun prefixOf(secret: String): String = secret.take(14)
    }
}
