package com.qualityverifier.data.chat.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    /**
     * Sent as a block list rather than a bare string so a cache breakpoint can be
     * attached to it. Render order is tools -> system -> messages, so a breakpoint here
     * caches everything ahead of the conversation.
     */
    val system: List<SystemBlock>,
    val messages: List<ApiMessage>,
    /**
     * Null rather than false when off, so a non-streaming request serialises to exactly
     * the bytes it did before streaming existed — `explicitNulls = false` drops the field
     * entirely. It is not part of the cached prefix either way (caching covers tools,
     * system and messages), but the two callers have to agree byte-for-byte and the
     * cheapest way to guarantee that is to emit nothing at all.
     */
    val stream: Boolean? = null,
)

@Serializable
data class SystemBlock(
    val type: String = "text",
    val text: String,
    @SerialName("cache_control") val cacheControl: CacheControl? = null,
)

/**
 * Marks a prompt-caching breakpoint. Caching is a prefix match, so everything up to the
 * marked block is reused on later requests: reads cost about a tenth of normal input,
 * writes a premium (1.25x at the default five-minute TTL, 2x at one hour).
 */
@Serializable
data class CacheControl(
    val type: String = "ephemeral",
    val ttl: String? = null,
) {
    companion object {
        /**
         * One hour rather than the five-minute default. The item walkthroughs send the
         * user away to take a photo — tipping a table over, finding someone to help lift
         * a sofa — so gaps between turns routinely exceed five minutes and a shorter
         * entry would expire mid-checklist. The doubled write premium needs three
         * requests to pay off, and a walkthrough is a dozen.
         */
        val ONE_HOUR = CacheControl(ttl = "1h")
    }
}

@Serializable
data class ApiMessage(
    val role: String,
    val content: List<ContentBlock>,
)

@Serializable
sealed class ContentBlock {
    @Serializable
    @SerialName("text")
    data class Text(
        val text: String,
        @SerialName("cache_control") val cacheControl: CacheControl? = null,
    ) : ContentBlock()

    @Serializable
    @SerialName("image")
    data class Image(
        val source: ImageSource,
        @SerialName("cache_control") val cacheControl: CacheControl? = null,
    ) : ContentBlock()
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
    val usage: Usage? = null,
)

/**
 * Token accounting. [cacheReadInputTokens] staying at zero across turns is the signal
 * that something is invalidating the prefix, so it is worth logging.
 * Total prompt size is the sum of all three input figures.
 */
@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int = 0,
    @SerialName("output_tokens") val outputTokens: Int = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0,
)

@Serializable
data class ErrorEnvelope(val error: ErrorBody? = null)

@Serializable
data class ErrorBody(val type: String? = null, val message: String? = null)
