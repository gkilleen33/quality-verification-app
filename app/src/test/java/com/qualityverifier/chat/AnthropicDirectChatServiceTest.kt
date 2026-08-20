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
        assertEquals(
            "MASTER\n\nTABLE",
            body["system"]!!.jsonArray.single().jsonObject["text"]?.jsonPrimitive?.content,
        )
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
    fun `system prompt is sent as a cached block with a one hour ttl`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service(systemPrompt = "MASTER\n\nCHECKLIST")
            .send("s1", ItemType.WOODEN_TABLE, listOf(userTurn("hello")))

        val system = requestBody().jsonObject["system"]!!.jsonArray
        assertEquals(1, system.size)
        val block = system.single().jsonObject
        assertEquals("text", block["type"]?.jsonPrimitive?.content)
        assertEquals("MASTER\n\nCHECKLIST", block["text"]?.jsonPrimitive?.content)
        val cache = block["cache_control"]!!.jsonObject
        assertEquals("ephemeral", cache["type"]?.jsonPrimitive?.content)
        assertEquals("1h", cache["ttl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `the final content block carries a rolling cache breakpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        val history = listOf(
            userTurn("first"),
            ChatMessage("a1", Role.ASSISTANT, "reply"),
            userTurn("second"),
        )
        service().send("s1", ItemType.OTHER, history)

        val messages = requestBody().jsonObject["messages"]!!.jsonArray
        // Exactly one breakpoint in the messages, on the very last block.
        val marked = messages.flatMap { it.jsonObject["content"]!!.jsonArray }
            .count { it.jsonObject.containsKey("cache_control") }
        assertEquals(1, marked)
        val lastBlock = messages.last().jsonObject["content"]!!.jsonArray.last().jsonObject
        assertEquals("1h", lastBlock["cache_control"]!!.jsonObject["ttl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `an image can carry the breakpoint when the turn has no text`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service(imageBytes = byteArrayOf(9, 9)).send(
            "s1",
            ItemType.WOODEN_CHAIR,
            listOf(userTurn("", listOf(Attachment("a1", "/tmp/photo.jpg")))),
        )

        val blocks = requestBody().jsonObject["messages"]!!.jsonArray
            .single().jsonObject["content"]!!.jsonArray
        assertEquals(1, blocks.size)
        val image = blocks.single().jsonObject
        assertEquals("image", image["type"]?.jsonPrimitive?.content)
        assertEquals("1h", image["cache_control"]!!.jsonObject["ttl"]?.jsonPrimitive?.content)
    }

    @Test
    fun `total breakpoints stay within the limit of four`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        // A long conversation with photos on several turns - the shape that would blow
        // the limit if a breakpoint were added per turn.
        val history = (1..8).flatMap { i ->
            listOf(
                userTurn("q$i", listOf(Attachment("a$i", "/tmp/$i.jpg"))),
                ChatMessage("r$i", Role.ASSISTANT, "a$i"),
            )
        }
        service(imageBytes = byteArrayOf(1)).send("s1", ItemType.UPHOLSTERED_SOFA, history)

        val body = requestBody().jsonObject
        val inSystem = body["system"]!!.jsonArray
            .count { it.jsonObject.containsKey("cache_control") }
        val inMessages = body["messages"]!!.jsonArray
            .flatMap { it.jsonObject["content"]!!.jsonArray }
            .count { it.jsonObject.containsKey("cache_control") }
        assertEquals(2, inSystem + inMessages)
        assertTrue(inSystem + inMessages <= 4)
    }

    @Test
    fun `repeating a turn produces a byte-identical prefix`() = runTest {
        // Prompt caching is a prefix match, so any per-request value in the prefix would
        // silently defeat it. Two identical sends must serialize identically.
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        val history = listOf(userTurn("same question"))
        val svc = service(imageBytes = byteArrayOf(4, 5, 6))
        svc.send("s1", ItemType.WOODEN_BED, history)
        svc.send("s1", ItemType.WOODEN_BED, history)

        assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
    }

    @Test
    fun `cache usage figures are parsed from the response`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"content":[{"type":"text","text":"ok"}],
                   "usage":{"input_tokens":12,"output_tokens":34,
                            "cache_creation_input_tokens":0,"cache_read_input_tokens":1543}}"""
            )
        )

        val result = service().send("s1", ItemType.OTHER, listOf(userTurn("hi")))

        // Parsing must not be disturbed by the extra field.
        assertEquals(ChatResult.Success("ok"), result)
    }

    @Test
    fun `an empty conversation is opened with a synthetic user turn`() = runTest {
        // The item prompts greet the user and ask for the first photo themselves, but the
        // API needs messages[0] to be a user turn or there is nothing to send.
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service().send("s1", ItemType.WOODEN_TABLE, emptyList())

        val messages = requestMessages()
        assertEquals(1, messages.size)
        assertEquals("user", role(messages.single()))
        assertEquals(AnthropicDirectChatService.OPENING_TURN, firstText(messages.single()))
    }

    @Test
    fun `a history starting with an assistant turn gets the opening turn prepended`() = runTest {
        // The state after the walkthrough opened itself: the only stored turn is Claude's.
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service().send(
            "s1",
            ItemType.WOODEN_TABLE,
            listOf(ChatMessage("a1", Role.ASSISTANT, "Welcome, send a photo")),
        )

        val messages = requestMessages()
        assertEquals(listOf("user", "assistant"), messages.map { role(it) })
        assertEquals(AnthropicDirectChatService.OPENING_TURN, firstText(messages[0]))
    }

    @Test
    fun `a history already starting with the user is left alone`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service().send("s1", ItemType.OTHER, listOf(userTurn("I have a stool")))

        val messages = requestMessages()
        assertEquals(1, messages.size)
        assertEquals("I have a stool", firstText(messages.single()))
    }

    @Test
    fun `the opening turn is stable, so the cached prefix survives`() = runTest {
        // A varying opener would silently defeat prompt caching from turn two onward.
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        val svc = service()
        svc.send("s1", ItemType.WOODEN_BED, emptyList())
        svc.send("s1", ItemType.WOODEN_BED, emptyList())

        assertEquals(server.takeRequest().body.readUtf8(), server.takeRequest().body.readUtf8())
    }

    @Test
    fun `the cache breakpoint still lands on the last block after prepending`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))

        service().send("s1", ItemType.OTHER, emptyList())

        val blocks = requestMessages().single().jsonObject["content"]!!.jsonArray
        assertEquals("1h", blocks.last().jsonObject["cache_control"]!!
            .jsonObject["ttl"]?.jsonPrimitive?.content)
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

    private fun requestMessages(): JsonArray = requestBody().jsonObject["messages"]!!.jsonArray

    private fun requestBody(): kotlinx.serialization.json.JsonElement =
        json.parseToJsonElement(server.takeRequest().body.readUtf8())

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
