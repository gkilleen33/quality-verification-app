package com.qualityverifier.data.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reassembles a streamed Anthropic reply from the SSE lines it arrives as.
 *
 * Deliberately knows nothing about HTTP. It is fed one line at a time and asked what that
 * line meant, which is what makes the awkward part — a stream that stops halfway, an
 * `error` event after two hundred good deltas, usage split across two events — testable
 * without a socket. `AnthropicClient` owns the socket and nothing else.
 *
 * Reads the payloads by hand rather than through a serialiser per event type. The event
 * set is open and versioned upstream: new event types appear, and a stream carrying one
 * we have never heard of must keep working rather than fail to decode. So this looks for
 * the four things it needs and ignores everything else, including `ping`.
 */
class MessageStream(private val json: Json = LENIENT) {

    private val body = StringBuilder()

    var model: String? = null
        private set
    var stopReason: String? = null
        private set
    var inputTokens: Int = 0
        private set
    var outputTokens: Int = 0
        private set
    var cacheCreationTokens: Int = 0
        private set
    var cacheReadTokens: Int = 0
        private set

    /** Set by an `error` event, which arrives with HTTP 200 already sent. */
    var errorMessage: String? = null
        private set

    /** True once `message_stop` has arrived. Anything short of it is a truncated stream. */
    var isComplete: Boolean = false
        private set

    val text: String get() = body.toString()

    /**
     * Feeds one line and returns the text it added, or null.
     *
     * A returned string is the increment, never the whole reply — the caller forwards it
     * and does not have to diff anything.
     */
    fun accept(line: String): String? {
        // Field names are `data:` with an optional single space. `event:` lines are
        // ignored on purpose: the same information is in the payload's own `type`, and
        // trusting one source rather than two removes a way for them to disagree.
        val payload = when {
            line.startsWith(DATA_PREFIX) -> line.removePrefix(DATA_PREFIX).trim()
            line.startsWith("data:") -> line.removePrefix("data:").trim()
            else -> return null
        }
        if (payload.isEmpty() || payload == DONE_SENTINEL) return null

        val root = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
            ?: return null

        return when (root.string("type")) {
            "message_start" -> {
                val message = root["message"]?.jsonObject
                model = message?.string("model") ?: model
                message?.get("usage")?.jsonObject?.let(::readUsage)
                null
            }

            "content_block_delta" -> {
                val delta = root["delta"]?.jsonObject ?: return null
                // Only text. A thinking delta or a tool-input delta is not part of the
                // reply the customer reads, and appending one would put the model's
                // working out in the chat bubble.
                if (delta.string("type") != "text_delta") return null
                val chunk = delta.string("text") ?: return null
                if (chunk.isEmpty()) return null
                body.append(chunk)
                chunk
            }

            "message_delta" -> {
                stopReason = root["delta"]?.jsonObject?.string("stop_reason") ?: stopReason
                // Output tokens arrive here, and only here — message_start reports the
                // input side before generation has happened.
                root["usage"]?.jsonObject?.let(::readUsage)
                null
            }

            "message_stop" -> {
                isComplete = true
                null
            }

            "error" -> {
                errorMessage = root["error"]?.jsonObject?.string("message")
                    ?: root.string("message")
                    ?: "The assistant stopped mid-answer"
                null
            }

            else -> null
        }
    }

    /**
     * Usage is cumulative per event and partial in both: whichever event carries a field
     * carries the latest value for it, so a zero must not overwrite a number we already
     * have. `message_delta` reports output tokens and repeats nothing else.
     */
    private fun readUsage(usage: kotlinx.serialization.json.JsonObject) {
        usage.int("input_tokens")?.let { inputTokens = it }
        usage.int("output_tokens")?.let { outputTokens = it }
        usage.int("cache_creation_input_tokens")?.let { cacheCreationTokens = it }
        usage.int("cache_read_input_tokens")?.let { cacheReadTokens = it }
    }

    private fun kotlinx.serialization.json.JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()

    private fun kotlinx.serialization.json.JsonObject.int(key: String): Int? =
        runCatching { this[key]?.jsonPrimitive?.content?.toInt() }.getOrNull()

    private companion object {
        const val DATA_PREFIX = "data: "

        /** Not an Anthropic event, but harmless to tolerate from a proxy in the path. */
        const val DONE_SENTINEL = "[DONE]"

        val LENIENT = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}
