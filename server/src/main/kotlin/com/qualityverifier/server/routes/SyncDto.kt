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
)

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
