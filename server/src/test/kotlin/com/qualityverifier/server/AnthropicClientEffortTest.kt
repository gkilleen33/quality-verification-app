package com.qualityverifier.server

import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.chat.AnthropicClient
import com.qualityverifier.server.chat.ClaudeResult
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * What AnthropicClient puts on the wire for a thinking effort (row 50): the route tests use a
 * fake client that builds its own JSON, so this holds the real one to sending the field when
 * asked and leaving it out otherwise.
 */
class AnthropicClientEffortTest {

    private lateinit var api: MockWebServer

    @Before
    fun setUp() {
        api = MockWebServer()
        api.start()
    }

    @After
    fun tearDown() {
        api.shutdown()
    }

    private val reply = """
        {"id":"m","model":"claude-sonnet-5-5","content":[{"type":"text","text":"Noted."}],
         "stop_reason":"end_turn","usage":{"input_tokens":1,"output_tokens":1}}
    """.trimIndent()

    private fun client() = AnthropicClient(
        client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build(),
        apiKey = { "test-key-not-real" },
        baseUrl = api.url("/v1/messages").toString(),
    )

    private val history = listOf(ChatMessage("u1", Role.USER, "Is the joint tight?"))

    @Test
    fun `row 50 - a Fundi Bora call sends output_config effort medium`() = runBlocking {
        api.enqueue(MockResponse().setBody(reply))
        val result = client().send("FUNDI PROMPT", history, { null }, null, "medium")
        assertTrue(result is ClaudeResult.Success)
        val body = api.takeRequest().body.readUtf8()
        assertTrue(body, body.endsWith("\"output_config\":{\"effort\":\"medium\"}}"))
    }

    @Test
    fun `row 50 - a call with no effort, as Kagua's, sends no output_config`() = runBlocking {
        api.enqueue(MockResponse().setBody(reply))
        client().send("KAGUA PROMPT", history, { null })
        val body = api.takeRequest().body.readUtf8()
        assertFalse(body, body.contains("output_config"))
    }

    /**
     * The bytes an assessment turn, a detail answer and a prefetch send (all three go through
     * these two calls), written out as they were before the translation call existed, so a
     * change to how that call is built cannot move any other request's cached prefix.
     */
    @Test
    fun `row 29 - assessment, detail and prefetch requests are byte-identical to before`() = runBlocking {
        val cache = """"cache_control":{"type":"ephemeral","ttl":"1h"}"""
        val fundi = """{"model":"claude-sonnet-5-5","max_tokens":16000,""" +
            """"system":[{"type":"text","text":"FUNDI PROMPT",$cache}],""" +
            """"messages":[{"role":"user","content":[{"type":"text","text":"Is the joint tight?",$cache}]}],""" +
            """"output_config":{"effort":"medium"}}"""
        val kagua = """{"model":"claude-sonnet-5-5","max_tokens":16000,""" +
            """"system":[{"type":"text","text":"KAGUA PROMPT",$cache}],""" +
            """"messages":[{"role":"user","content":[{"type":"text","text":"Is the joint tight?",$cache}]}]}"""

        api.enqueue(MockResponse().setBody(reply))
        client().send("FUNDI PROMPT", history, { null }, null, "medium")
        assertEquals(fundi, api.takeRequest().body.readUtf8())

        api.enqueue(MockResponse().setBody(reply))
        client().send("KAGUA PROMPT", history, { null })
        assertEquals(kagua, api.takeRequest().body.readUtf8())
    }

    @Test
    fun `row 29 - a translation call carries no cache breakpoint`() = runBlocking {
        api.enqueue(MockResponse().setBody(reply))
        val result = client().sendUncached("TRANSLATE PROMPT", history, "low")
        assertTrue(result is ClaudeResult.Success)
        val expected = """{"model":"claude-sonnet-5-5","max_tokens":16000,""" +
            """"system":[{"type":"text","text":"TRANSLATE PROMPT"}],""" +
            """"messages":[{"role":"user","content":[{"type":"text","text":"Is the joint tight?"}]}],""" +
            """"output_config":{"effort":"low"}}"""
        assertEquals(expected, api.takeRequest().body.readUtf8())
    }
}
