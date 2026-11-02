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
