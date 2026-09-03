package com.qualityverifier.data.chat

import com.qualityverifier.data.chat.dto.ApiMessage
import com.qualityverifier.data.chat.dto.CacheControl
import com.qualityverifier.data.chat.dto.ContentBlock
import com.qualityverifier.data.chat.dto.ImageSource
import com.qualityverifier.data.chat.dto.MessagesRequest
import com.qualityverifier.data.chat.dto.SystemBlock
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import kotlinx.serialization.json.Json

/**
 * Turns a conversation into an Anthropic request.
 *
 * Shared between the phone and the server for one reason: **prompt caching is a
 * byte-exact prefix match.** Two implementations that agree on the shape but differ in
 * field order, default handling, or where the breakpoint sits produce different bytes,
 * the cache silently stops hitting, and the only symptom is a larger bill. Nothing
 * throws, no test fails, no log line complains.
 *
 * During the Phase 2 transition both callers exist at once — the phone still talks to
 * the API directly for users on an old build, while the server assembles the same
 * conversation for users on a new one. Afterwards only the server calls this, but by
 * then the shape is settled and shared.
 */
object AnthropicRequest {

    const val MESSAGES_URL = "https://api.anthropic.com/v1/messages"
    const val VERSION = "2023-06-01"
    const val MODEL = "claude-sonnet-5"
    const val MAX_TOKENS = 4096

    /**
     * Supplies the opening user turn the API requires.
     *
     * The item protocols open the conversation themselves, but `messages[0]` must be a
     * user turn, so there is nothing to send until the customer speaks. A fixed constant
     * rather than anything generated: it sits at the very front of the cached prefix, so
     * a timestamp or a session id here would invalidate the cache on every single turn.
     * Never shown in the transcript.
     */
    const val OPENING_TURN = "Let's get started."

    /**
     * The serialisation settings, fixed here rather than configured per caller.
     *
     * This is not tidiness. `encodeDefaults` and `explicitNulls` both change the emitted
     * JSON, so a caller that built its own Json with different settings would produce a
     * different prefix from the same conversation and lose every cache hit.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    /**
     * @param imageBytes returns the bytes for an attachment, or null to skip it. The
     *   phone reads a file; the server reads a blob by content hash. An attachment whose
     *   bytes cannot be found is dropped rather than failing the turn — a missing photo
     *   should cost one image, not the whole assessment.
     */
    fun build(
        systemPrompt: String,
        history: List<ChatMessage>,
        imageBytes: (Attachment) -> ByteArray?,
        encodeBase64: (ByteArray) -> String,
        model: String = MODEL,
        maxTokens: Int = MAX_TOKENS,
        /**
         * Asks for the reply as a series of deltas. Changes nothing about the prefix, so
         * a streamed turn still reads the same cache a non-streamed one wrote.
         */
        stream: Boolean = false,
    ): MessagesRequest = MessagesRequest(
        model = model,
        maxTokens = maxTokens,
        stream = if (stream) true else null,
        // Two breakpoints, well inside the limit of four. The system prompt is identical
        // for every conversation about this item type; the message prefix grows by one
        // exchange per turn, and a walkthrough is a dozen turns carrying several photos,
        // so re-processing it each time is the dominant cost.
        system = listOf(
            SystemBlock(text = systemPrompt, cacheControl = CacheControl.ONE_HOUR)
        ),
        messages = history
            .toApiMessages(imageBytes, encodeBase64)
            .withOpeningTurn()
            .withCacheBreakpointOnLastBlock(),
    )

    /**
     * Images before text within a turn, always.
     *
     * The order is part of the cached prefix, so it has to be deterministic — and images
     * first is also what the protocols assume when they say "look at the photo attached
     * to their opening message".
     */
    private fun List<ChatMessage>.toApiMessages(
        imageBytes: (Attachment) -> ByteArray?,
        encodeBase64: (ByteArray) -> String,
    ): List<ApiMessage> = mapNotNull { message ->
        val blocks = buildList {
            if (message.role == Role.USER) {
                message.attachments.forEach { attachment ->
                    val bytes = imageBytes(attachment) ?: return@forEach
                    add(
                        ContentBlock.Image(
                            ImageSource(
                                mediaType = attachment.mimeType,
                                data = encodeBase64(bytes),
                            )
                        )
                    )
                }
            }
            if (message.text.isNotBlank()) add(ContentBlock.Text(message.text))
        }
        if (blocks.isEmpty()) {
            // A turn with no text and no readable photo would be an empty content array,
            // which the API rejects outright.
            null
        } else {
            ApiMessage(
                role = if (message.role == Role.USER) ROLE_USER else ROLE_ASSISTANT,
                content = blocks,
            )
        }
    }

    private fun List<ApiMessage>.withOpeningTurn(): List<ApiMessage> =
        if (firstOrNull()?.role == ROLE_USER) {
            this
        } else {
            listOf(ApiMessage(ROLE_USER, listOf(ContentBlock.Text(OPENING_TURN)))) + this
        }

    /**
     * Puts a cache breakpoint on the final content block, so the next turn reads this
     * whole conversation back from cache instead of re-processing it.
     *
     * A breakpoint searches back at most 20 content blocks for a prior entry. One
     * exchange adds at most seven blocks — five photos, a text block, the reply — so a
     * single rolling breakpoint stays comfortably inside that window.
     */
    private fun List<ApiMessage>.withCacheBreakpointOnLastBlock(): List<ApiMessage> {
        val last = lastOrNull() ?: return this
        if (last.content.isEmpty()) return this
        val marked = last.content.toMutableList()
        marked[marked.lastIndex] = when (val block = marked.last()) {
            is ContentBlock.Text -> block.copy(cacheControl = CacheControl.ONE_HOUR)
            is ContentBlock.Image -> block.copy(cacheControl = CacheControl.ONE_HOUR)
        }
        return dropLast(1) + last.copy(content = marked)
    }

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
}
