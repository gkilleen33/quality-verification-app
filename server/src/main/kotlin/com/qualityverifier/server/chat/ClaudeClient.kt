package com.qualityverifier.server.chat

import com.qualityverifier.data.chat.AnthropicRequest
import com.qualityverifier.data.chat.dto.ApiMessage
import com.qualityverifier.data.chat.dto.ContentBlock
import com.qualityverifier.data.chat.dto.ErrorEnvelope
import com.qualityverifier.data.chat.dto.ImageSource
import com.qualityverifier.data.chat.dto.MessagesResponse
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.slf4j.LoggerFactory
import java.io.IOException
import java.util.Base64

/** What a turn cost, straight from the API. Written to usage_events either way. */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheCreationTokens: Int = 0,
    val cacheReadTokens: Int = 0,
) {
    /** A turn that looked at photographs is several requests, billed as their sum. */
    operator fun plus(other: TokenUsage) = TokenUsage(
        inputTokens + other.inputTokens,
        outputTokens + other.outputTokens,
        cacheCreationTokens + other.cacheCreationTokens,
        cacheReadTokens + other.cacheReadTokens,
    )
}

enum class UpstreamError {
    /** Our key was rejected. Nothing the customer can do; page somebody. */
    AUTH,
    RATE_LIMIT,
    OVERLOADED,
    SERVER,
    /** Malformed or oversized request — a bug on our side, not a transient failure. */
    REQUEST,

    /**
     * The model ran out of room and stopped mid-answer.
     *
     * Its own kind because it is the one failure that arrives as a 200 with plausible
     * content in it. Treated as a failure rather than stored, for two reasons: an answer
     * the model did not finish is not an answer, and a truncated turn would join the
     * cached conversation prefix and be re-sent on every later turn — so the model would
     * spend the rest of the assessment reading its own half-written JSON.
     */
    TRUNCATED,
    NETWORK,
    UNKNOWN,
}

sealed interface ClaudeResult {
    data class Success(val text: String, val usage: TokenUsage, val model: String?) : ClaudeResult
    data class Failure(
        val error: UpstreamError,
        val message: String,
        val httpStatus: Int? = null,
        val usage: TokenUsage? = null,
    ) : ClaudeResult
}

/**
 * Sends a conversation to Claude.
 *
 * An interface with one implementation, deliberately. IT provisioned the instance role
 * with Bedrock invoke permissions and expects us there eventually; when that happens the
 * swap is a second class behind this, not a rewrite of the route. Same reasoning as
 * AppContainer on the phone.
 */
interface ClaudeClient {
    /**
     * @param photos offered to a compacted conversation, whose photographs are no longer
     *   in [history], so the model can look at them again. Null offers no tool at all.
     */
    suspend fun send(
        systemPrompt: String,
        history: List<ChatMessage>,
        imageBytes: (Attachment) -> ByteArray?,
        photos: PhotoShelf? = null,
    ): ClaudeResult
}

class AnthropicClient(
    private val client: OkHttpClient,
    /** Read per call, not captured, so rotating the parameter needs only a restart. */
    private val apiKey: () -> String?,
    private val baseUrl: String = AnthropicRequest.MESSAGES_URL,
) : ClaudeClient {

    private val log = LoggerFactory.getLogger(AnthropicClient::class.java)

    override suspend fun send(
        systemPrompt: String,
        history: List<ChatMessage>,
        imageBytes: (Attachment) -> ByteArray?,
        photos: PhotoShelf?,
    ): ClaudeResult = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isNullOrBlank()) {
            // Ours, not the customer's. Distinct from every other failure because it
            // needs an operator rather than a retry.
            log.error("No Anthropic API key available; check /kagua/anthropic/api-key")
            return@withContext ClaudeResult.Failure(UpstreamError.AUTH, "No API key configured")
        }
        val encode: (ByteArray) -> String = { Base64.getEncoder().encodeToString(it) }

        // One request, unless the model asks to look at photographs, in which case the
        // lookup and its answer are appended and the request goes again. Never stored:
        // the turn that is kept is the final answer, so the next turn does not carry the
        // photographs this one looked at.
        var trailing = emptyList<ApiMessage>()
        var usage = TokenUsage()
        var lookups = 0
        while (true) {
            val payload = AnthropicRequest.build(
                systemPrompt = systemPrompt,
                history = history,
                imageBytes = imageBytes,
                encodeBase64 = encode,
                tools = photos?.let { listOf(PhotoShelf.TOOL) },
                trailing = trailing,
            )
            val step = sendWithRetry(key, AnthropicRequest.json.encodeToString(payload))
            val response = when (step) {
                is Step.Failed -> return@withContext step.failure.withUsage(usage)
                is Step.Replied -> step
            }
            usage += response.usage
            val toolCalls = response.decoded.content.filter { it.type == TOOL_USE }
            if (photos == null || response.decoded.stopReason != STOP_TOOL_USE || toolCalls.isEmpty()) {
                return@withContext finish(response.decoded, usage)
            }
            // It was told there are no more and asked anyway. Whatever text it wrote is
            // the answer; with none, the turn fails rather than looping on our bill.
            if (lookups > MAX_LOOKUPS) return@withContext finish(response.decoded, usage)

            lookups++
            log.info("Model asked to look again at photos ({} of {} lookups)", lookups, MAX_LOOKUPS)
            trailing = trailing + listOf(
                ApiMessage(AnthropicRequest.ROLE_ASSISTANT, response.decoded.content.toBlocks()),
                ApiMessage(
                    AnthropicRequest.ROLE_USER,
                    toolCalls.map { call -> answer(call, photos, lookups, imageBytes, encode) },
                ),
            )
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private sealed interface Step {
        data class Replied(val decoded: MessagesResponse, val usage: TokenUsage) : Step
        data class Failed(val failure: ClaudeResult.Failure) : Step
    }

    private suspend fun sendWithRetry(key: String, body: String): Step {
        var attempt = 0
        while (true) {
            val step = attemptSend(key, body)
            val retryable = step is Step.Failed && when (step.failure.error) {
                UpstreamError.RATE_LIMIT, UpstreamError.OVERLOADED, UpstreamError.SERVER -> true
                else -> false
            }
            if (!retryable || attempt >= MAX_RETRIES) return step
            attempt++
            delay(RETRY_DELAY_MILLIS * attempt)
        }
    }

    /** The assistant's turn, replayed exactly: tool_use ids must match their results. */
    private fun List<com.qualityverifier.data.chat.dto.ResponseBlock>.toBlocks(): List<ContentBlock> =
        mapNotNull { block ->
            when (block.type) {
                "text" -> block.text?.takeIf { it.isNotBlank() }?.let { ContentBlock.Text(it) }
                TOOL_USE -> ContentBlock.ToolUse(
                    id = block.id.orEmpty(),
                    name = block.name.orEmpty(),
                    input = block.input ?: kotlinx.serialization.json.JsonObject(emptyMap()),
                )
                else -> null
            }
        }

    private fun answer(
        call: com.qualityverifier.data.chat.dto.ResponseBlock,
        photos: PhotoShelf,
        lookups: Int,
        imageBytes: (Attachment) -> ByteArray?,
        encode: (ByteArray) -> String,
    ): ContentBlock.ToolResult {
        val id = call.id.orEmpty()
        // Past the limit, told so rather than refused at the API: the conversation stays
        // well-formed and the model answers from what it has already seen.
        if (lookups > MAX_LOOKUPS) {
            return ContentBlock.ToolResult(
                id, listOf(ContentBlock.Text("No more photos this turn. Answer from what you have.")),
                isError = true,
            )
        }
        if (call.name != PhotoShelf.TOOL_NAME) {
            return ContentBlock.ToolResult(
                id, listOf(ContentBlock.Text("There is no tool called ${call.name}.")), isError = true,
            )
        }
        val lookup = photos.resolve(call.input ?: kotlinx.serialization.json.JsonObject(emptyMap()))
        val blocks = buildList {
            lookup.found.forEach { (number, attachment) ->
                val bytes = imageBytes(attachment) ?: run {
                    add(ContentBlock.Text("Photo $number could not be read."))
                    return@forEach
                }
                add(ContentBlock.Text("Photo $number"))
                add(ContentBlock.Image(ImageSource(mediaType = attachment.mimeType, data = encode(bytes))))
            }
            lookup.problem?.let { add(ContentBlock.Text(it)) }
        }
        return ContentBlock.ToolResult(
            id,
            blocks.ifEmpty { listOf(ContentBlock.Text("Nothing to show.")) },
            isError = if (lookup.found.isEmpty()) true else null,
        )
    }

    private fun attemptSend(key: String, body: String): Step {
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", AnthropicRequest.VERSION)
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) decode(text) else Step.Failed(failureFor(response.code, text))
            }
        } catch (e: IOException) {
            log.warn("Upstream request failed", e)
            Step.Failed(ClaudeResult.Failure(UpstreamError.NETWORK, "Could not reach the assistant"))
        }
    }

    private fun decode(body: String): Step {
        val decoded = runCatching { AnthropicRequest.json.decodeFromString<MessagesResponse>(body) }
            .getOrElse { e ->
                log.error("Could not decode a 200 response", e)
                return Step.Failed(ClaudeResult.Failure(UpstreamError.UNKNOWN, "Unreadable response"))
            }

        val usage = decoded.usage?.let {
            TokenUsage(
                it.inputTokens, it.outputTokens,
                it.cacheCreationInputTokens, it.cacheReadInputTokens,
            )
        } ?: TokenUsage()

        // cacheRead staying at zero across turns of one conversation is the only signal
        // that something is invalidating the prefix. Logged every turn so the pattern is
        // visible without instrumenting anything else.
        log.info(
            "tokens in={} cacheWrite={} cacheRead={} out={} stop={}",
            usage.inputTokens, usage.cacheCreationTokens,
            usage.cacheReadTokens, usage.outputTokens, decoded.stopReason,
        )
        return Step.Replied(decoded, usage)
    }

    private fun finish(decoded: MessagesResponse, usage: TokenUsage): ClaudeResult {
        val text = decoded.content.filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .trim()

        if (decoded.stopReason == STOP_MAX_TOKENS) {
            // Not stored, and said out loud. This is what silently broke every full
            // assessment: the reply looked fine, its fenced block did not close, the
            // block was dropped as unparseable, and nothing anywhere reported a problem.
            log.error(
                "Reply hit max_tokens at {} output tokens; discarding a truncated answer",
                usage.outputTokens,
            )
            return ClaudeResult.Failure(
                UpstreamError.TRUNCATED, "The answer was cut off", usage = usage,
            )
        }

        if (text.isBlank()) {
            // A 200 with nothing usable in it: a refusal, or only non-text blocks. Not a
            // transient failure, so no retry.
            log.warn("Upstream returned no text; stop_reason={}", decoded.stopReason)
            return ClaudeResult.Failure(
                UpstreamError.UNKNOWN, "The assistant returned no answer", usage = usage,
            )
        }
        return ClaudeResult.Success(text, usage, decoded.model)
    }

    private fun failureFor(status: Int, body: String): ClaudeResult.Failure {
        val apiMessage = runCatching {
            AnthropicRequest.json.decodeFromString<ErrorEnvelope>(body).error?.message
        }.getOrNull()

        val error = when (status) {
            401, 403 -> UpstreamError.AUTH
            400, 413, 422 -> UpstreamError.REQUEST
            429 -> UpstreamError.RATE_LIMIT
            529 -> UpstreamError.OVERLOADED
            in 500..599 -> UpstreamError.SERVER
            else -> UpstreamError.UNKNOWN
        }
        // Logged at error for the ones that are our fault, warn for the transient ones.
        val detail = apiMessage ?: body.take(300)
        if (error == UpstreamError.AUTH || error == UpstreamError.REQUEST) {
            log.error("Upstream {} {}: {}", status, error, detail)
        } else {
            log.warn("Upstream {} {}: {}", status, error, detail)
        }
        return ClaudeResult.Failure(error, detail, httpStatus = status)
    }

    private companion object {
        /** The API's own word for "I ran out of room". */
        const val STOP_MAX_TOKENS = "max_tokens"
        const val STOP_TOOL_USE = "tool_use"
        const val TOOL_USE = "tool_use"

        /** Two looks per turn is a closer look; more would be re-reading the assessment. */
        const val MAX_LOOKUPS = 2

        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_RETRIES = 1
        const val RETRY_DELAY_MILLIS = 1500L
    }
}

private fun ClaudeResult.Failure.withUsage(earlier: TokenUsage): ClaudeResult.Failure =
    if (earlier == TokenUsage()) this else copy(usage = (usage ?: TokenUsage()) + earlier)
