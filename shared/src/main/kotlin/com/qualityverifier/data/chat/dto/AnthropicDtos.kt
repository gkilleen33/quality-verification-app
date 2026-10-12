package com.qualityverifier.data.chat.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

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
     * Null, and therefore absent from the JSON, on every request that offers no tool.
     * Tools render ahead of the system prompt, so a request that sometimes carried one
     * and sometimes did not would split the cache — which is why it is only ever offered
     * to a conversation that has been compacted, and then on every turn of it.
     */
    val tools: List<ToolDefinition>? = null,
)

@Serializable
data class ToolDefinition(
    val name: String,
    val description: String,
    @SerialName("input_schema") val inputSchema: JsonObject,
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

    /** The model asking for a tool, echoed back exactly as it arrived. */
    @Serializable
    @SerialName("tool_use")
    data class ToolUse(
        val id: String,
        val name: String,
        val input: JsonObject,
        @SerialName("cache_control") val cacheControl: CacheControl? = null,
    ) : ContentBlock()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(
        @SerialName("tool_use_id") val toolUseId: String,
        val content: List<ContentBlock>,
        @SerialName("is_error") val isError: Boolean? = null,
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
    /** The three a tool_use block carries. */
    val id: String? = null,
    val name: String? = null,
    val input: JsonObject? = null,
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
