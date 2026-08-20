package com.qualityverifier.chat

import com.qualityverifier.data.chat.AnthropicDirectChatService
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ImageBytesSource
import com.qualityverifier.data.keys.ApiKeyStore
import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class AnthropicDirectChatServiceTest {

    private lateinit var server: MockWebServer

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun service(
        apiKey: String? = "sk-ant-test-key",
        systemPrompt: String = "SYSTEM",
        imageBytes: ByteArray? = null,
    ) = AnthropicDirectChatService(
        client = OkHttpClient.Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(2, TimeUnit.SECONDS)
            .build(),
        apiKeyStore = FakeKeyStore(apiKey),
        promptRepository = FakePromptRepository(systemPrompt),
        images = FakeImageStore(imageBytes),
        json = json,
        baseUrl = server.url("/v1/messages").toString(),
        encodeBase64 = { Base64.getEncoder().encodeToString(it) },
    )

    private fun userTurn(text: String, attachments: List<Attachment> = emptyList()) =
        ChatMessage("m-${text.hashCode()}", Role.USER, text, attachments)

    @Test
    fun `a successful reply returns the concatenated text blocks`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"The joints are loose."}]}"""
            )
        )

        val result = service().send("s1", ItemType.WOODEN_TABLE, listOf(userTurn("check this")))

        assertEquals(ChatResult.Success("The joints are loose."), result)
    }

    @Test
    fun `unknown response block types are skipped rather than failing the parse`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"thinking","thinking":"hmm"},
                   {"type":"text","text":"Looks solid."}]}"""
            )
        )

        assertEquals(
            ChatResult.Success("Looks solid."),
            service().send("s1", ItemType.OTHER, listOf(userTurn("hello"))),
        )
    }

    @Test
    fun `request carries the model, token cap, system prompt and headers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service(systemPrompt = "MASTER\n\nTABLE")
            .send("s1", ItemType.WOODEN_TABLE, listOf(userTurn("hello")))

        val request = server.takeRequest()
        assertEquals("sk-ant-test-key", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))

        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("claude-sonnet-5", body["model"]?.jsonPrimitive?.content)
        assertEquals(4096, body["max_tokens"]?.jsonPrimitive?.content?.toInt())
        assertEquals("MASTER\n\nTABLE", body["system"]?.jsonPrimitive?.content)
    }

    @Test
    fun `history keeps its order and roles`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        val history = listOf(
            userTurn("first"),
            ChatMessage("a1", Role.ASSISTANT, "reply"),
            userTurn("second"),
        )
        service().send("s1", ItemType.OTHER, history)

        val messages = requestMessages()
        assertEquals(3, messages.size)
        assertEquals(listOf("user", "assistant", "user"), messages.map { role(it) })
        assertEquals("first", firstText(messages[0]))
        assertEquals("reply", firstText(messages[1]))
        assertEquals("second", firstText(messages[2]))
    }

    @Test
    fun `images are sent as base64 blocks ahead of the text in the same turn`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        val bytes = byteArrayOf(1, 2, 3, 4)

        service(imageBytes = bytes).send(
            "s1",
            ItemType.WOODEN_CHAIR,
            listOf(userTurn("look", listOf(Attachment("a1", "/tmp/photo.jpg")))),
        )

        val blocks = requestMessages().single().jsonObject["content"]!!.jsonArray
        assertEquals(2, blocks.size)

        val image = blocks[0].jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        val source = image["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
        assertEquals(
            Base64.getEncoder().encodeToString(bytes),
            source["data"]?.jsonPrimitive?.content,
        )

        assertEquals("text", blocks[1].jsonObject["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `a turn whose only image is unreadable is dropped instead of sent empty`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        // imageBytes = null models a deleted or corrupt file.
        service(imageBytes = null).send(
            "s1",
            ItemType.OTHER,
            listOf(
                userTurn("", listOf(Attachment("a1", "/tmp/gone.jpg"))),
                userTurn("still here"),
            ),
        )

        val messages = requestMessages()
        assertEquals(1, messages.size)
        assertEquals("still here", firstText(messages[0]))
    }

    @Test
    fun `a missing key fails as an auth error without any network call`() = runTest {
        val result = service(apiKey = null).send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatErrorKind.AUTH, (result as ChatResult.Failure).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `HTTP 401 maps to an auth error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatErrorKind.AUTH, (result as ChatResult.Failure).kind)
        assertTrue(result.message.contains("Settings"))
    }

    @Test
    fun `HTTP 400 surfaces the API's own message`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"error":{"type":"invalid_request_error","message":"image too large"}}""")
        )

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatErrorKind.REQUEST, (result as ChatResult.Failure).kind)
        assertEquals("image too large", result.message)
    }

    @Test
    fun `a rate limit is retried once and then reported`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(429))

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatErrorKind.RATE_LIMIT, (result as ChatResult.Failure).kind)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a retried server error that then succeeds returns the reply`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"recovered"}]}"""))

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatResult.Success("recovered"), result)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `an unreachable server maps to a network error`() = runTest {
        server.shutdown()

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatErrorKind.NETWORK, (result as ChatResult.Failure).kind)
        assertTrue(result.message.contains("saved"))
    }

    @Test
    fun `an empty reply is reported rather than saved as a blank message`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[]}"""))

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        assertEquals(ChatErrorKind.UNKNOWN, (result as ChatResult.Failure).kind)
    }

    private fun requestMessages(): JsonArray =
        json.parseToJsonElement(server.takeRequest().body.readUtf8())
            .jsonObject["messages"]!!.jsonArray

    private fun role(message: kotlinx.serialization.json.JsonElement): String =
        message.jsonObject["role"]!!.jsonPrimitive.content

    private fun firstText(message: kotlinx.serialization.json.JsonElement): String? =
        message.jsonObject["content"]!!.jsonArray
            .map { it.jsonObject }
            .firstOrNull { it["type"]?.jsonPrimitive?.content == "text" }
            ?.get("text")?.jsonPrimitive?.content

    private class FakeKeyStore(private val key: String?) : ApiKeyStore {
        override fun hasKey() = key != null
        override fun get() = key
        override fun set(key: String) = Unit
        override fun clear() = Unit
    }

    private class FakePromptRepository(private val prompt: String) : PromptRepository {
        override suspend fun systemPromptFor(itemType: ItemType) = prompt
        override suspend fun clearCache() = Unit
    }

    /** Returns fixed bytes for any path, or null to model an unreadable file. */
    private class FakeImageStore(private val bytes: ByteArray?) : ImageBytesSource {
        override fun bytesForUpload(file: java.io.File): ByteArray? = bytes
    }
}
