package com.qualityverifier.server.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
    /**
     * Which app this account is for: `buyer` or `fundi`.
     *
     * The first thing any analysis has to split on. A fundi working through a morning's
     * output and a buyer checking one table are different populations, and a figure
     * averaged over both describes neither. Do not confuse it with `account_type`, which
     * says whether a business lets walk-in customers use its handset.
     */
    val audience: String,
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
    /**
     * Which app this assessment was conducted in.
     *
     * The session's own rather than the account's: an assessment is the research record,
     * and a record that has to join to a profile to say what it was is a worse record.
     */
    val audience: String,
    /**
     * The versionCode of the app that started this assessment (V24).
     *
     * Null for an assessment made by v0.3.0 or older, which did not send its version:
     * "not known", never "version 0". Defaulted so the field's arrival changes no caller.
     */
    @SerialName("app_version_code") val appVersionCode: Int? = null,
    /**
     * Why the maker opened this assessment (`evaluate`, `reevaluate`, `diagnose`,
     * `work_in_progress`, `verify_fix`, `new_issue`, `customer_return`, `learn`, `other`),
     * decoded from the session's intake code. Only `evaluate` and `reevaluate` are full
     * assessments with a verdict; the rest are partial checks, which analysis keeps apart.
     *
     * Null for Kagua, and for a Fundi Bora code this server cannot read: "not known".
     */
    val purpose: String? = null,
    /** The language the maker chose for this assessment (`en`, `sw`), with [purpose]. */
    val language: String? = null,
    /**
     * The piece this assessment is of, so a re-assessment joins to the first one. Null for
     * Kagua, and for an assessment started before pieces existed or whose piece is gone.
     */
    @SerialName("piece_id") val pieceId: String? = null,
    /**
     * SHA-256 of the full system prompt the server assembled when the assessment started.
     * The prompt files change without an app release, so this is what says which version
     * of the protocol wrote the replies. Null where nothing recorded it.
     */
    @SerialName("prompt_sha") val promptSha: String? = null,
)

/**
 * One problem an assessment found, on the single-assessment route only: listing them for a
 * page of up to 200 assessments would mean reading every reply.
 *
 * Read from the stored replies on each request (`reports/DefectList.kt`), in the order the
 * reply gave them, worst first. The wording stays in the messages; these are the labels.
 */
@Serializable
data class ApiProblem(
    val title: String,
    /** structural, level, surface, material, upholstery, hardware, or other; null if not given. */
    val area: String?,
    /** serious, moderate, minor, or cosmetic; null if not given. */
    val severity: String?,
    /**
     * The skill it belongs to (frame_squareness, joint_fit, finishing, surface, material,
     * hardware). In replies written before the prompt wrote each problem once, a verdict
     * defect takes it from the diagnosis finding with the same title, and is null when no
     * finding has that title. Null for Kagua.
     */
    val dimension: String?,
    /**
     * The part of the piece the server puts it down to: frame, joinery, upholstery,
     * finishing, or assembly. Null when the labels do not say whose work it was (material,
     * structural or level with no dimension, other).
     */
    @SerialName("default_part") val defaultPart: String?,
)

@Serializable
data class ApiMessage(
    val role: String,
    val text: String,
    @SerialName("created_at") val createdAt: Long,
    /** SHA-256 hashes, in the order the customer took them. Fetch with /api/v1/photos. */
    val photos: List<String>,
    /**
     * Each photo's camera metadata (V25), in the same order as [photos]: the first entry
     * belongs to the first hash. Null for a photo sent without any, which is every photo
     * from v0.3.0 or older and every photo of a deleted account. Keys are
     * `PhotoMeta.FIELDS`; a key the camera did not fill is absent.
     *
     * A list beside [photos] rather than objects in its place, so a consumer reading the
     * hashes is unchanged. On this route only: the assessment list carries no messages.
     */
    @SerialName("photo_meta") val photoMeta: List<JsonObject?> = emptyList(),
)

@Serializable
data class ApiAssessmentDetail(
    val assessment: ApiAssessment,
    val messages: List<ApiMessage>,
    val feedback: ApiTesterFeedback?,
    /**
     * The assessment's problems, for every purpose. Empty when it found none or wrote no
     * verdict or diagnosis; null when they cannot be known, because the newest diagnosis
     * block would not decode (the stored defect count is null then too). Filled in by the
     * route from [messages], not by the store.
     */
    val problems: List<ApiProblem>? = null,
    /**
     * Who worked on it, as the manager answered "Who worked on this?" (V23). Empty for
     * Kagua and for an assessment where nobody was named, which reads as "not said"
     * rather than "nobody". Joins to `/api/v1/workers` on `worker_id`.
     */
    val workers: List<ApiSessionWorker> = emptyList(),
)

/**
 * One `session_workers` row. A null [workerId] is the manager, who has no roster entry; a
 * null [part] names the worker on the whole piece.
 */
@Serializable
data class ApiSessionWorker(
    @SerialName("worker_id") val workerId: String?,
    /** frame, joinery, upholstery, finishing, assembly, or other; null for the whole piece. */
    val part: String?,
)

/**
 * One link between a worker's account and a studio's account (V31), from the append-only
 * record: made when both sides had listed each other, ended when either side archived its
 * entry or an account was deleted. A pair that linked again after ending is two rows.
 */
@Serializable
data class ApiWorkerLink(
    /** The worker's account, as `/api/v1/users` lists it. */
    @SerialName("worker_user_id") val workerUserId: String,
    /** The studio's account: the manager whose roster named the worker. */
    @SerialName("studio_user_id") val studioUserId: String,
    /** The roster entry the link was made on, as `/api/v1/workers` lists it. */
    @SerialName("worker_entry_id") val workerEntryId: String,
    /** `employee` or `temporary`, as the roster entry said when the link was made. */
    val employment: String,
    /** The server's clock when the link was first recorded. */
    @SerialName("linked_since") val linkedSince: Long,
    /** When it ended, or null while it holds. */
    @SerialName("ended_at") val endedAt: Long?,
)

/**
 * One roster entry (V23, V30), for the report that sets out each worker's work. The
 * manager is `user_id`; the worker has no account here, and [phone] is what links one
 * once both sides have listed each other (V31, `/api/v1/worker-links`).
 */
@Serializable
data class ApiWorker(
    val id: String,
    /** The manager's account, as `/api/v1/users` lists it. */
    @SerialName("user_id") val userId: String,
    /** Null once the manager's account is deleted, as the manager's own name is. */
    val name: String?,
    /** E.164, or null for an entry added without a number, and null after deletion. */
    val phone: String?,
    /** `employee` or `temporary`. Every entry from before V30 is an employee. */
    val employment: String,
    /** When the manager archived this entry, or null while it is on the roster. */
    @SerialName("archived_at") val archivedAt: Long?,
    @SerialName("created_at") val createdAt: Long,
    /** The server's receipt time of the last write, never the phone's clock (V18). */
    @SerialName("updated_at") val updatedAt: Long,
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
