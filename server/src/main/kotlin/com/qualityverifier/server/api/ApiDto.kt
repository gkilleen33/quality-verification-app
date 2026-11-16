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
    /**
     * On what footing [userId] assessed the piece (V34), written when the assessment
     * started: `maker` for the piece's own maker, `manager` for the account that ran the
     * studio the piece was labelled for (a manager assessing a linked worker's piece, row
     * 11). Null for Kagua and for an assessment of no piece. A manager's wrong pick, a
     * different piece from the one the worker logged, is found among the `manager` rows.
     */
    @SerialName("assessed_as") val assessedAs: String? = null,
    /** The final assessment of the finished piece, made before a customer report (V34, row 27). */
    val final: Boolean = false,
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
    /**
     * The tools to hand when the assessment started (V35, release scope row 10), as the
     * server worked them out: the maker's own, and on a piece labelled for a studio, the
     * maker's whole list and the studio manager's. Empty for Kagua, for an assessment of no
     * piece, and for one made before v0.6.0. Never shown to the fundi or sent to Claude.
     */
    val tools: List<ApiSessionTool> = emptyList(),
)

/** One `session_tools` row: a tool kind, whose list it came from (`maker`, `studio`), and its owner. */
@Serializable
data class ApiSessionTool(
    val kind: String,
    val source: String,
    /** `worker`, `manager`, `studio` or `rental`. */
    val owner: String,
)

/**
 * One bench photo at setup (V35, release scope row 5): who took it, when, where when the
 * fundi allowed location, and the tool list Claude drafted from it, before the fundi
 * confirmed or rejected each tool. The confirmed list is the account's tools.
 */
@Serializable
data class ApiBenchPhoto(
    val id: String,
    @SerialName("user_id") val userId: String,
    /** Fetch the bytes with /api/v1/photos. */
    val sha256: String,
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("location_accuracy_m") val locationAccuracyM: Double?,
    /** The server's receipt time. */
    @SerialName("taken_at") val takenAt: Long,
    /** `{"tools":[{"kind":..., "seen":...}]}`, or null when the call failed. */
    val draft: JsonObject?,
    @SerialName("prompt_sha") val promptSha: String?,
    val model: String?,
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

/**
 * One piece (V14): what groups a maker's assessments. No label: what the maker calls a
 * piece can be a customer's name, and the PIs have not yet said whether it may leave the
 * server (plan v7 section 9). Assessments join on `piece_id`.
 */
@Serializable
data class ApiPiece(
    val id: String,
    /** The maker's account, as `/api/v1/users` lists it. */
    @SerialName("user_id") val userId: String,
    @SerialName("item_type_id") val itemTypeId: String,
    @SerialName("created_at") val createdAt: Long,
    /**
     * The studio the piece is for (V34, row 1): an entry of the maker's own list of studios
     * they work for (`worker_studios.id`), or null for "My own job" and for a piece never
     * asked. The entry's number is what links it to the studio's account
     * (`/api/v1/worker-links`).
     */
    @SerialName("studio_entry_id") val studioEntryId: String? = null,
    /**
     * When the maker answered "Which studio is this for?": at creation, or later for a piece
     * made before v0.6.0. Null for a piece never asked, which is not "My own job".
     */
    @SerialName("studio_chosen_at") val studioChosenAt: Long? = null,
)

/**
 * One change of a piece's studio label after the piece was made (V34, row 2): the first
 * answer for a piece never asked (`first_choice`), or its one `correction`. Append-only.
 */
@Serializable
data class ApiPieceStudioChange(
    val id: Long,
    @SerialName("piece_id") val pieceId: String,
    /** The maker, who made the change. */
    @SerialName("user_id") val userId: String,
    /** `first_choice` or `correction`. */
    val kind: String,
    /** Null for none: a first choice, or "My own job". */
    @SerialName("old_studio_entry_id") val oldStudioEntryId: String?,
    @SerialName("new_studio_entry_id") val newStudioEntryId: String?,
    /** The server's clock. */
    val at: Long,
)

/** One answer to "Which studio are you at today?" (V34, row 9): one per account and Nairobi day. */
@Serializable
data class ApiStudioDay(
    @SerialName("user_id") val userId: String,
    /** Nairobi date, "2026-11-16". */
    val day: String,
    /** An entry of the account's own list of studios, or null for its own workshop. */
    @SerialName("studio_entry_id") val studioEntryId: String?,
    @SerialName("answered_at") val answeredAt: Long,
)

/**
 * One PDF report the server issued (V32): which piece or worker, which kind, which
 * assessments and problems it printed, and the hash of the file that left the server.
 *
 * `defect_snapshot` is what was printed, kept for when the replies are purged or the parser
 * changes: each assessment's level and headline, each problem's labels and the part it was
 * printed under, and each worker row's part and `worker_id`. The names the report printed
 * stay in the database row and are not carried here, as the roster's names are not: join
 * `worker_id` to `/api/v1/workers`.
 */
@Serializable
data class ApiReport(
    val id: String,
    /** The piece, or null for a `worker_work` report, which spans pieces. */
    @SerialName("piece_id") val pieceId: String?,
    /** The account that issued it. */
    @SerialName("user_id") val userId: String,
    /** customer, part_issues, part_fixes, full, to_manager, or worker_work. */
    val kind: String,
    /** Every assessment the report covered. */
    @SerialName("session_ids") val sessionIds: List<String>,
    /** The parts a part report covered; null for every other kind. */
    val parts: List<String>?,
    /** The roster worker a `worker_work` report is about; null for every other kind. */
    @SerialName("worker_id") val workerId: String?,
    /** Every problem printed, as `<message_id>:<ordinal>` in the reply the list was read from. */
    @SerialName("defect_refs") val defectRefs: List<String>,
    @SerialName("defect_snapshot") val defectSnapshot: JsonObject,
    val sha256: String,
    @SerialName("byte_size") val byteSize: Int,
    @SerialName("issued_at") val issuedAt: Long,
)

/**
 * One PesaLog transaction (V33). `counterparty_code` is the same HMAC as in Fundi Bora's
 * M-Pesa records, so it joins across the two apps and across accounts; the number or name it
 * came from was never stored. Join `user_id` to `/api/v1/users`: a PesaLog account is a
 * Fundi Bora account.
 */
@Serializable
data class ApiTrackerTransaction(
    val id: Long,
    @SerialName("user_id") val userId: String,
    /** `mpesa` or `airtel`. */
    val network: String,
    /** The transaction code the message printed; unique per account and network. */
    val code: String,
    /** `in`, `out`, or `other` for money that stayed the person's own. */
    val direction: String,
    val type: String,
    @SerialName("amount_kes") val amountKes: Double,
    @SerialName("fee_kes") val feeKes: Double?,
    @SerialName("balance_after_kes") val balanceAfterKes: Double?,
    /** When the phone received the message, on the phone's clock. */
    @SerialName("occurred_at") val occurredAt: Long,
    @SerialName("counterparty_code") val counterpartyCode: String?,
    /** `phone` or `name`: what the code was computed from. */
    @SerialName("counterparty_basis") val counterpartyBasis: String?,
    @SerialName("parser_version") val parserVersion: Int,
    @SerialName("app_version") val appVersion: Int,
    /** The server's clock, at the first upload. */
    @SerialName("received_at") val receivedAt: Long,
    val category: String?,
    /** Free text the person typed; can name people. */
    val comment: String?,
    @SerialName("annotated_at") val annotatedAt: Long?,
)

/** How many mobile money messages PesaLog could not read, for one account, network and day. */
@Serializable
data class ApiTrackerUnreadCount(
    @SerialName("user_id") val userId: String,
    val network: String,
    /** Nairobi date, "2026-11-12". */
    val day: String,
    val unread: Int,
    @SerialName("parser_version") val parserVersion: Int,
    @SerialName("app_version") val appVersion: Int,
    @SerialName("updated_at") val updatedAt: Long,
)

/** One deletion a person asked for from PesaLog's Settings, and how many rows it removed. */
@Serializable
data class ApiTrackerDeletion(
    val id: Long,
    @SerialName("user_id") val userId: String,
    @SerialName("deleted_at") val deletedAt: Long,
    @SerialName("transactions_deleted") val transactionsDeleted: Int,
    @SerialName("unread_days_deleted") val unreadDaysDeleted: Int,
    @SerialName("app_version") val appVersion: Int?,
)

@Serializable
data class ApiError(val error: String, val detail: String? = null)
