package com.qualityverifier.server.routes

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionSummaryDto(
    val id: String,
    @SerialName("item_type_id") val itemTypeId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("updated_at") val updatedAt: Long,
    val preview: String,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("verdict_level_id") val verdictLevelId: String? = null,
    @SerialName("verdict_language") val verdictLanguage: String? = null,
    @SerialName("previous_session_id") val previousSessionId: String? = null,
    @SerialName("intake_answers") val intakeAnswers: String? = null,
    @SerialName("piece_id") val pieceId: String? = null,
    @SerialName("piece_label") val pieceLabel: String? = null,
    /** Who worked on it, by roster id and part. Names come from GET /v1/fundi/workers. */
    val workers: List<SessionWorkerDto> = emptyList(),
    /**
     * False for an assessment this account made on a linked worker's piece (V34, row 11):
     * the piece is the worker's, so its label and correction are not this account's to show
     * or change, and the three studio fields below are left out. An app before v0.6.0
     * ignores all five.
     */
    @SerialName("piece_mine") val pieceMine: Boolean = true,
    /** The studio entry the piece is for; null for "My own job" or never asked. */
    @SerialName("piece_studio_id") val pieceStudioId: String? = null,
    /** Whether the maker answered "Which studio is this for?" at all. */
    @SerialName("piece_studio_chosen") val pieceStudioChosen: Boolean = false,
    /** Whether the one correction has been used. */
    @SerialName("piece_studio_corrected") val pieceStudioCorrected: Boolean = false,
    /** The final assessment of the finished piece, before a customer report (row 27). */
    val final: Boolean = false,
)

@Serializable
data class SessionWorkerDto(
    /** Null is the manager. */
    @SerialName("worker_id") val workerId: String? = null,
    val part: String? = null,
)

/** The body of PUT /v1/sessions/{id}/workers: the whole set, replacing what was there. */
@Serializable
data class SessionWorkersRequest(val workers: List<WorkerSlotDto> = emptyList())

@Serializable
data class SessionListDto(val sessions: List<SessionSummaryDto>)

@Serializable
data class MessageDto(
    val id: String,
    val role: String,
    val text: String,
    val ordinal: Int,
    @SerialName("created_at") val createdAt: Long,
    /** Content hashes, in the order the customer took them. Fetch via GET /v1/blobs. */
    val blobs: List<String> = emptyList(),
    /** Written by the app on the user's behalf, so a rebuilt history can show it compactly. */
    val composed: Boolean = false,
)

@Serializable
data class SessionDetailDto(
    val session: SessionSummaryDto,
    val messages: List<MessageDto>,
)

/** The body of PUT /v1/sessions/{id}/tablet-end (V39, row 12). */
@Serializable
data class TabletEndRequest(val end: String = "")

/** The body of POST /v1/me/password-check: the shop owner's password on the tablet (row 12). */
@Serializable
data class PasswordCheckRequest(val password: String = "")

@Serializable
data class ChangePasswordRequest(
    @SerialName("current_password") val currentPassword: String = "",
    @SerialName("new_password") val newPassword: String = "",
)

/**
 * An evaluator's critique of one assessment.
 *
 * Defaults on everything so a malformed body is a validation failure with a message rather
 * than a deserialisation exception with none — the same reasoning as the other DTOs here.
 */
@Serializable
data class TesterFeedbackRequest(
    @SerialName("session_id") val sessionId: String = "",
    /** "yes" | "no" | "unsure" */
    val mistakes: String = "",
    @SerialName("mistakes_detail") val mistakesDetail: String? = null,
    @SerialName("advice_stars") val adviceStars: Int = 0,
    @SerialName("item_quality") val itemQuality: Int = 0,
    @SerialName("extra_feedback") val extraFeedback: String? = null,
)
