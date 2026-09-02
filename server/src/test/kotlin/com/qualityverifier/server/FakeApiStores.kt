package com.qualityverifier.server

import com.qualityverifier.server.api.ApiAssessment
import com.qualityverifier.server.api.ApiAssessmentDetail
import com.qualityverifier.server.api.ApiKeyRow
import com.qualityverifier.server.api.ApiKeyStore
import com.qualityverifier.server.api.ApiStore
import com.qualityverifier.server.api.ApiTesterFeedback
import com.qualityverifier.server.api.ApiUser
import com.qualityverifier.server.api.NewApiKey
import com.qualityverifier.server.api.PostgresApiKeyStore
import java.time.Instant

/**
 * An in-memory key store.
 *
 * Uses the real hashing and key generation so that what a test signs in with is a key of
 * the shape production issues — a fake that accepted any string would exercise the routes
 * and not the credential.
 */
class FakeApiKeyStore(labels: List<String> = listOf("research pull")) : ApiKeyStore {
    /** Live keys, secret to id. Populated at construction so tests have one to present. */
    val issued = mutableMapOf<String, String>()
    private val rows = mutableMapOf<String, ApiKeyRow>()
    private val revoked = mutableSetOf<String>()
    val used = mutableListOf<String>()

    init {
        labels.forEach { create(it) }
    }

    /** Non-suspending twin for setup, so `init` can use it. */
    private fun create(label: String): NewApiKey {
        val secret = PostgresApiKeyStore.newSecret()
        // A real uuid, because the revoke route validates the shape before using it. A
        // readable placeholder made the revoke test fail against working code — the same
        // trap as using "mine" for a session id.
        val id = java.util.UUID.randomUUID().toString()
        issued[secret] = id
        rows[id] = ApiKeyRow(
            id = id,
            label = label,
            prefix = PostgresApiKeyStore.prefixOf(secret),
            createdAt = Instant.now(),
            createdByEmail = "admin@example.com",
            lastUsedAt = null,
            revokedAt = null,
        )
        return NewApiKey(id, secret, PostgresApiKeyStore.prefixOf(secret))
    }

    /** The first issued key, which is what most tests present. */
    fun anyKey(): String = issued.keys.first()

    override suspend fun create(label: String, createdBy: String?): NewApiKey = create(label)

    override suspend fun idFor(secret: String): String? =
        issued[secret]?.takeIf { it !in revoked }

    override suspend fun markUsed(id: String) { used += id }

    override suspend fun keys(): List<ApiKeyRow> = rows.values.map {
        if (it.id in revoked) it.copy(revokedAt = Instant.now()) else it
    }

    override suspend fun revoke(id: String): Boolean {
        if (id !in rows || id in revoked) return false
        revoked += id
        return true
    }
}

/** Fixed rows, so a route test asserts on shape and filtering rather than on SQL. */
class FakeApiStore(
    private val users: List<ApiUser> = listOf(API_USER),
    private val assessments: List<ApiAssessment> = listOf(API_ASSESSMENT),
    private val detail: ApiAssessmentDetail? = null,
    private val feedback: List<ApiTesterFeedback> = emptyList(),
    private val photos: Set<String> = emptySet(),
) : ApiStore {
    var lastLimit: Int? = null
        private set
    var lastTestersOnly: Boolean? = null
        private set
    var lastUpdatedSince: Long? = null
        private set

    override suspend fun users(limit: Int, offset: Int): List<ApiUser> {
        lastLimit = limit
        return users
    }

    override suspend fun assessments(
        limit: Int,
        offset: Int,
        userId: String?,
        itemTypeId: String?,
        testersOnly: Boolean,
        updatedSince: Long?,
    ): List<ApiAssessment> {
        lastLimit = limit
        lastTestersOnly = testersOnly
        lastUpdatedSince = updatedSince
        return assessments
    }

    override suspend fun assessment(id: String): ApiAssessmentDetail? = detail

    override suspend fun testerFeedback(limit: Int, offset: Int): List<ApiTesterFeedback> {
        lastLimit = limit
        return feedback
    }

    override suspend fun photoExists(sha256: String): Boolean = sha256 in photos
}

val API_USER = ApiUser(
    id = "11111111-2222-3333-4444-555555555555",
    phone = "+256700000000",
    name = "A Buyer",
    accountType = "business",
    businessName = "Kampala Furniture",
    latitude = 0.3476,
    longitude = 32.5825,
    locationAccuracyM = 8.0,
    isTester = false,
    createdAt = 1_700_000_000_000,
    assessments = 3,
    deleted = false,
)

val API_ASSESSMENT = ApiAssessment(
    id = "2ff77920-3928-46e5-8a77-5e16c1e901c6",
    userId = API_USER.id,
    itemTypeId = "wooden-table",
    createdAt = 1_700_000_000_000,
    updatedAt = 1_700_000_100_000,
    messageCount = 2,
    photoCount = 1,
    verdictLevelId = "fair",
    byTester = false,
    hasTesterFeedback = false,
    deletedByUser = false,
)

/**
 * An AdminStore that only records audit rows.
 *
 * The API's use of AdminStore is exactly one method, so everything else throws rather than
 * returning a plausible default: a test that reaches one of these has wandered somewhere it
 * did not mean to, and should say so.
 */
class RecordingAudit : com.qualityverifier.server.admin.AdminStore {
    val audits = mutableListOf<com.qualityverifier.server.admin.AuditRow>()

    override suspend fun audit(
        adminId: String?,
        adminEmail: String,
        action: String,
        target: String?,
        detail: String?,
        ip: String?,
    ) {
        audits += com.qualityverifier.server.admin.AuditRow(
            adminEmail = adminEmail,
            action = action,
            target = target,
            detail = detail,
            ip = ip,
            createdAt = Instant.now(),
        )
    }

    private fun no(): Nothing = error("the data API does not use this")

    override suspend fun overview() = no()
    override suspend fun credentialsFor(email: String) = no()
    override suspend fun createAdmin(
        email: String, name: String, passwordHash: String, createdBy: String?,
    ) = no()
    override suspend fun confirmTotp(adminId: String) = no()
    override suspend fun recordSignIn(adminId: String) = no()
    override suspend fun recordFailure(adminId: String, lockFor: java.time.Duration, threshold: Int) = no()
    override suspend fun setPasswordHash(adminId: String, hash: String) = no()
    override suspend fun resetTotp(adminId: String) = no()
    override suspend fun trustDevice(
        adminId: String, tokenHash: String, label: String?, expiresAt: Instant,
    ) = no()
    override suspend fun trustedDevice(tokenHash: String) = no()
    override suspend fun touchTrustedDevice(id: String) = no()
    override suspend fun trustedDevices(adminId: String) = no()
    override suspend fun revokeTrustedDevices(adminId: String) = no()
    override suspend fun setDisabled(adminId: String, disabled: Boolean) = no()
    override suspend fun setTester(userId: String, isTester: Boolean) = no()
    override suspend fun admins() = no()
    override suspend fun activeAdminCount() = no()
    override suspend fun invites() = no()
    override suspend fun createInvite(code: String, label: String?, grantsTester: Boolean) = no()
    override suspend fun revokeInvite(code: String) = no()
    override suspend fun users(limit: Int, offset: Int, search: String?) = no()
    override suspend fun sessions(
        limit: Int, offset: Int, userId: String?, itemTypeId: String?, testersOnly: Boolean,
    ) = no()
    override suspend fun sessionHeader(sessionId: String) = no()
    override suspend fun conversation(sessionId: String) = no()
    override suspend fun blobExists(sha: String) = no()
    override suspend fun auditTrail(limit: Int, offset: Int) = no()
}
