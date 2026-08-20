package com.qualityverifier.data.chat.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ApiMessage>,
)

@Serializable
data class ApiMessage(
    val role: String,
    val content: List<ContentBlock>,
)

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(val source: ImageSource) : ContentBlock()
}

@Serializable
data class ImageSource(
    val type: String = "base64",
    @SerialName("media_type") val mediaType: String,
    val data: String,
)

/**
 * Response blocks are decoded loosely rather than as a sealed hierarchy: the API may
 * add block types (thinking, tool use) that this app should skip rather than fail on.
 */
@Serializable
data class ResponseBlock(
    val type: String,
    val text: String? = null,
)

@Serializable
data class MessagesResponse(
    val id: String? = null,
    val model: String? = null,
    val content: List<ResponseBlock> = emptyList(),
    @SerialName("stop_reason") val stopReason: String? = null,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(val type: String? = null, val message: String? = null)
