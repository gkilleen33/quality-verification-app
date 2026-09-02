package com.qualityverifier.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What the read-only API returns.
 *
 * Deliberately its own DTOs rather than reusing the phone's. Those are a wire format the
 * app depends on, and a research consumer pulling a dataset wants different things — full
 * identifiers, explicit nulls where a value is genuinely absent, and stability across app
 * releases. Sharing them would couple a published dataset to whatever the phone needed
 * next.
 *
 * Every timestamp is epoch milliseconds, matching the rest of the API rather than
 * introducing a second date convention for one consumer to get wrong.
 */
@Serializable
data class ApiPage<T>(
    val items: List<T>,
    /** True when another page exists. Cursor-free: offset paging is enough at this size. */
    @SerialName("has_more") val hasMore: Boolean,
    val limit: Int,
    val offset: Int,
)

@Serializable
data class ApiUser(
    val id: String,
    val phone: String?,
    val name: String?,
    @SerialName("account_type") val accountType: String?,
    @SerialName("business_name") val businessName: String?,
    /**
     * Where the business is, captured only if they registered while standing in it.
     *
     * Null is the common case and means "not captured" — never "no premises". The accuracy
     * is in metres and is not decoration: a fix good to 2km and one good to 5m are
     * indistinguishable in the coordinates, and only one of them supports any spatial
     * claim.
     */
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("location_accuracy_m") val locationAccuracyM: Double?,
    @SerialName("is_tester") val isTester: Boolean,
    @SerialName("created_at") val createdAt: Long,
    val assessments: Int,
    /**
     * True when this account was closed by its owner.
     *
     * Its identifying columns are null in that case, and permanently so. Worth reading as
     * "these fields will never be filled in" rather than "not captured".
     */
    val deleted: Boolean,
)

@Serializable
data class ApiAssessment(
    val id: String,
    @SerialName("user_id") val userId: String?,
    @SerialName("item_type_id") val itemTypeId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("photo_count") val photoCount: Int,
    @SerialName("verdict_level_id") val verdictLevelId: String?,
    @SerialName("by_tester") val byTester: Boolean,
    @SerialName("has_tester_feedback") val hasTesterFeedback: Boolean,
    @SerialName("deleted_by_user") val deletedByUser: Boolean,
)

@Serializable
data class ApiMessage(
    val role: String,
    val text: String,
    @SerialName("created_at") val createdAt: Long,
    /** SHA-256 hashes, in the order the customer took them. Fetch with /api/v1/photos. */
    val photos: List<String>,
)

@Serializable
data class ApiAssessmentDetail(
    val assessment: ApiAssessment,
    val messages: List<ApiMessage>,
    val feedback: ApiTesterFeedback?,
)

@Serializable
data class ApiTesterFeedback(
    /** The merge key, in both directions. */
    @SerialName("session_id") val sessionId: String,
    val mistakes: String,
    @SerialName("mistakes_detail") val mistakesDetail: String?,
    @SerialName("advice_stars") val adviceStars: Int,
    @SerialName("item_quality") val itemQuality: Int,
    @SerialName("extra_feedback") val extraFeedback: String?,
)

@Serializable
data class ApiError(val error: String, val detail: String? = null)
