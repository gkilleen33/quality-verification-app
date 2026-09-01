package com.qualityverifier.data.chat

import android.util.Base64
import android.util.Log
import com.qualityverifier.data.chat.dto.ErrorEnvelope
import com.qualityverifier.data.chat.dto.MessagesResponse
import com.qualityverifier.data.keys.ApiKeyStore
import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException

/**
 * Phase 1 implementation: calls `POST https://api.anthropic.com/v1/messages` directly
 * using the API key from [ApiKeyStore]. No streaming.
 *
 * Deleted wholesale in Phase 2 and replaced by a server-proxy implementation of
 * [ChatService].
 */
class AnthropicDirectChatService(
    private val client: OkHttpClient,
    private val apiKeyStore: ApiKeyStore,
    private val promptRepository: PromptRepository,
    private val images: ImageBytesSource,
    private val json: Json,
    private val baseUrl: String = AnthropicRequest.MESSAGES_URL,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Injected so request building can be tested off-device. */
    private val encodeBase64: (ByteArray) -> String = { Base64.encodeToString(it, Base64.NO_WRAP) },
) : ChatService {

    override suspend fun send(
        sessionId: String,
        itemType: ItemType,
        history: List<ChatMessage>,
    ): ChatResult = withContext(io) {
        val apiKey = apiKeyStore.get()
            ?: return@withContext ChatResult.Failure(
                ChatErrorKind.AUTH,
                "No API key saved. Add your Anthropic API key in Settings.",
            )

        // Assembled by the shared builder, not here: prompt caching is a byte-exact
        // prefix match, so the phone and the server have to agree down to field order.
        val payload = AnthropicRequest.build(
            systemPrompt = promptRepository.systemPromptFor(itemType),
            history = history,
            imageBytes = { attachment -> images.bytesForUpload(File(attachment.path)) },
            encodeBase64 = encodeBase64,
        )
        val body = json.encodeToString(payload)

        // Rate limits and 5xx get one backed-off retry; everything else fails fast.
        var attempt = 0
        var result: ChatResult
        while (true) {
            result = attemptSend(apiKey, body)
            val retryable = result is ChatResult.Failure &&
                (result.kind == ChatErrorKind.RATE_LIMIT || result.kind == ChatErrorKind.SERVER)
            if (!retryable || attempt >= MAX_RETRIES) break
            attempt++
            delay(RETRY_DELAY_MILLIS * attempt)
        }
        result
    }

    private fun attemptSend(apiKey: String, body: String): ChatResult {
        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", AnthropicRequest.VERSION)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    parseSuccess(responseBody)
                } else {
                    failureFor(response.code, responseBody)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Request failed", e)
            ChatResult.Failure(
                ChatErrorKind.NETWORK,
                "No internet connection. Your message was saved — tap retry when you're back online.",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected failure", e)
            ChatResult.Failure(ChatErrorKind.UNKNOWN, "Something went wrong. Please try again.")
        }
    }

    private fun parseSuccess(body: String): ChatResult {
        val decoded = runCatching { json.decodeFromString<MessagesResponse>(body) }.getOrNull()
            ?: return ChatResult.Failure(
                ChatErrorKind.UNKNOWN,
                "Could not read the response. Please try again.",
            )
        decoded.usage?.let { usage ->
            // The one reliable way to tell caching is working. If cacheRead stays at 0
            // across turns of one conversation, something is changing the prefix.
            Log.i(
                TAG,
                "tokens in=${usage.inputTokens} cacheWrite=${usage.cacheCreationInputTokens} " +
                    "cacheRead=${usage.cacheReadInputTokens} out=${usage.outputTokens}",
            )
        }
        val text = decoded.content
            .filter { it.type == "text" }
            .mapNotNull { it.text }
            .joinToString("\n\n")
            .trim()
        return if (text.isEmpty()) {
            ChatResult.Failure(ChatErrorKind.UNKNOWN, "The assistant returned an empty reply.")
        } else {
            ChatResult.Success(text)
        }
    }

    private fun failureFor(code: Int, body: String): ChatResult.Failure {
        val apiMessage = runCatching {
            json.decodeFromString<ErrorEnvelope>(body).error?.message
        }.getOrNull()

        return when (code) {
            401, 403 -> ChatResult.Failure(
                ChatErrorKind.AUTH,
                "Your API key was rejected. Check it in Settings.",
            )
            400, 413, 422 -> ChatResult.Failure(
                ChatErrorKind.REQUEST,
                apiMessage ?: "The request was rejected. Try sending fewer or smaller photos.",
            )
            429 -> ChatResult.Failure(
                ChatErrorKind.RATE_LIMIT,
                "Too many requests right now. Please wait a moment and try again.",
            )
            in 500..599 -> ChatResult.Failure(
                ChatErrorKind.SERVER,
                "The service is busy. Please try again in a moment.",
            )
            else -> ChatResult.Failure(
                ChatErrorKind.UNKNOWN,
                apiMessage ?: "Request failed (HTTP $code).",
            )
        }
    }

    /**
     * Maps local history onto API turns. Images ride along in the user turn that owns
     * them, ahead of the text — the API reads image-then-text more reliably.
     * Turns that end up with no content at all are dropped, since the API rejects them.
     */
    companion object {

        private const val MAX_RETRIES = 1
        private const val RETRY_DELAY_MILLIS = 1500L
        private const val TAG = "ChatService"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
