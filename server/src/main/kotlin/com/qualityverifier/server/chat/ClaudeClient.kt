package com.qualityverifier.server.chat

import com.qualityverifier.data.chat.AnthropicRequest
import com.qualityverifier.data.chat.dto.ErrorEnvelope
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
)

enum class UpstreamError {
    /** Our key was rejected. Nothing the customer can do; page somebody. */
    AUTH,
    RATE_LIMIT,
    OVERLOADED,
    SERVER,
    /** Malformed or oversized request — a bug on our side, not a transient failure. */
    REQUEST,
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
    suspend fun send(
        systemPrompt: String,
        history: List<ChatMessage>,
        imageBytes: (Attachment) -> ByteArray?,
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
    ): ClaudeResult = withContext(Dispatchers.IO) {
        val key = apiKey()
        if (key.isNullOrBlank()) {
            // Ours, not the customer's. Distinct from every other failure because it
            // needs an operator rather than a retry.
            log.error("No Anthropic API key available; check /kagua/anthropic/api-key")
            return@withContext ClaudeResult.Failure(UpstreamError.AUTH, "No API key configured")
        }

        val payload = AnthropicRequest.build(
            systemPrompt = systemPrompt,
            history = history,
            imageBytes = imageBytes,
            encodeBase64 = { Base64.getEncoder().encodeToString(it) },
        )
        val body = AnthropicRequest.json.encodeToString(payload)

        var attempt = 0
        while (true) {
            val result = attemptSend(key, body)
            val retryable = result is ClaudeResult.Failure && when (result.error) {
                UpstreamError.RATE_LIMIT, UpstreamError.OVERLOADED, UpstreamError.SERVER -> true
                else -> false
            }
            if (!retryable || attempt >= MAX_RETRIES) return@withContext result
            attempt++
            delay(RETRY_DELAY_MILLIS * attempt)
        }
        @Suppress("UNREACHABLE_CODE") error("unreachable")
    }

    private fun attemptSend(key: String, body: String): ClaudeResult {
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("x-api-key", key)
            .addHeader("anthropic-version", AnthropicRequest.VERSION)
            .post(body.toRequestBody(JSON))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) parseSuccess(text) else failureFor(response.code, text)
            }
        } catch (e: IOException) {
            log.warn("Upstream request failed", e)
            ClaudeResult.Failure(UpstreamError.NETWORK, "Could not reach the assistant")
        }
    }

    private fun parseSuccess(body: String): ClaudeResult {
        val decoded = runCatching { AnthropicRequest.json.decodeFromString<MessagesResponse>(body) }
            .getOrElse { e ->
                log.error("Could not decode a 200 response", e)
                return ClaudeResult.Failure(UpstreamError.UNKNOWN, "Unreadable response")
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

        val text = decoded.content.filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n")
            .trim()

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

    private fun failureFor(status: Int, body: String): ClaudeResult {
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
        val JSON = "application/json; charset=utf-8".toMediaType()
        const val MAX_RETRIES = 1
        const val RETRY_DELAY_MILLIS = 1500L
    }
}
