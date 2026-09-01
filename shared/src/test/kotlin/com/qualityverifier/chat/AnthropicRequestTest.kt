package com.qualityverifier.chat

import com.qualityverifier.data.chat.AnthropicRequest
import com.qualityverifier.data.chat.dto.ContentBlock
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Request assembly, which both the phone and the server use.
 *
 * These tests exist because prompt caching fails silently. A change here that alters the
 * emitted bytes throws nothing, breaks no feature, and produces no log line — it just
 * stops the cache hitting, and the only place it shows up is the invoice. So the
 * properties the cache depends on are asserted rather than assumed.
 */
class AnthropicRequestTest {

    private fun bytes(attachment: Attachment): ByteArray? =
        if (attachment.path == "missing") null else byteArrayOf(1, 2, 3)

    private fun encode(bytes: ByteArray): String = "b64(${bytes.size})"

    private fun build(history: List<ChatMessage>, prompt: String = "SYSTEM") =
        AnthropicRequest.build(prompt, history, ::bytes, ::encode)

    @Test
    fun `the system prompt carries a one-hour breakpoint`() {
        val request = build(listOf(ChatMessage("1", Role.USER, "hello")))

        val system = request.system.single()
        assertEquals("SYSTEM", system.text)
        assertEquals("ephemeral", system.cacheControl?.type)
        // An hour, not the five-minute default: the walkthrough sends somebody away to
        // tip a table over, and a shorter entry expires mid-checklist.
        assertEquals("1h", system.cacheControl?.ttl)
    }

    @Test
    fun `exactly one breakpoint in the messages, on the very last block`() {
        val request = build(
            listOf(
                ChatMessage("1", Role.USER, "first"),
                ChatMessage("2", Role.ASSISTANT, "reply"),
                ChatMessage("3", Role.USER, "second"),
            )
        )

        val marked = request.messages.flatMap { it.content }.filter { block ->
            when (block) {
                is ContentBlock.Text -> block.cacheControl != null
                is ContentBlock.Image -> block.cacheControl != null
            }
        }
        assertEquals("a rolling breakpoint means exactly one", 1, marked.size)
        assertEquals(request.messages.last().content.last(), marked.single())
    }

    @Test
    fun `the same conversation assembles to identical bytes every time`() {
        // The whole point. Anything non-deterministic here — a map iteration order, a
        // timestamp, a UUID — costs every cache hit for every user.
        val history = listOf(
            ChatMessage("1", Role.USER, "hello", listOf(Attachment("a", "/tmp/one.jpg"))),
            ChatMessage("2", Role.ASSISTANT, "reply"),
        )

        val first = AnthropicRequest.json.encodeToString(build(history))
        val second = AnthropicRequest.json.encodeToString(build(history))

        assertEquals(first, second)
    }

    @Test
    fun `an earlier turn's content is unchanged when a later turn is added`() {
        // The property caching actually depends on: the prefix must be stable as the
        // conversation grows. The breakpoint marker moves each turn, which is the
        // documented rolling pattern and does not affect the match — but the *content*
        // of earlier turns must be byte-identical.
        val first = listOf(
            ChatMessage("1", Role.USER, "hello", listOf(Attachment("a", "/tmp/one.jpg"))),
            ChatMessage("2", Role.ASSISTANT, "reply"),
        )
        val extended = first + ChatMessage("3", Role.USER, "and another")

        fun contentOf(history: List<ChatMessage>) =
            build(history).messages.take(2).map { message ->
                message.role to message.content.map { block ->
                    when (block) {
                        is ContentBlock.Text -> "text:" + block.text
                        is ContentBlock.Image -> "image:" + block.source.data
                    }
                }
            }

        assertEquals(contentOf(first), contentOf(extended))
    }

    @Test
    fun `a conversation that starts with the assistant gets the fixed opening turn`() {
        // The API requires messages[0] to be a user turn, but the protocols open the
        // conversation themselves.
        val request = build(listOf(ChatMessage("1", Role.ASSISTANT, "Welcome")))

        assertEquals(2, request.messages.size)
        assertEquals("user", request.messages.first().role)
        val opening = request.messages.first().content.single() as ContentBlock.Text
        assertEquals(AnthropicRequest.OPENING_TURN, opening.text)
    }

    @Test
    fun `a conversation that already starts with the customer is left alone`() {
        val request = build(listOf(ChatMessage("1", Role.USER, "I am buying this.")))

        assertEquals(1, request.messages.size)
        val text = request.messages.single().content.single() as ContentBlock.Text
        assertEquals("I am buying this.", text.text)
    }

    @Test
    fun `the opening turn is a constant, so it cannot invalidate the prefix`() {
        // It sits at the very front of the cached prefix. A timestamp or a session id
        // here would lose every hit on every turn for every user.
        val once = build(listOf(ChatMessage("1", Role.ASSISTANT, "a")))
        val twice = build(listOf(ChatMessage("1", Role.ASSISTANT, "a")))
        assertEquals(
            (once.messages.first().content.single() as ContentBlock.Text).text,
            (twice.messages.first().content.single() as ContentBlock.Text).text,
        )
    }

    @Test
    fun `images come before text within a turn`() {
        val request = build(
            listOf(
                ChatMessage(
                    "1", Role.USER, "here are the photos",
                    listOf(Attachment("a", "/tmp/one.jpg"), Attachment("b", "/tmp/two.jpg")),
                )
            )
        )

        val blocks = request.messages.single().content
        assertTrue(blocks[0] is ContentBlock.Image)
        assertTrue(blocks[1] is ContentBlock.Image)
        assertTrue(blocks[2] is ContentBlock.Text)
    }

    @Test
    fun `an unreadable photo costs one image, not the whole turn`() {
        val request = build(
            listOf(
                ChatMessage(
                    "1", Role.USER, "two photos, one gone",
                    listOf(Attachment("a", "/tmp/one.jpg"), Attachment("b", "missing")),
                )
            )
        )

        val blocks = request.messages.single().content
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is ContentBlock.Image)
        assertTrue(blocks[1] is ContentBlock.Text)
    }

    @Test
    fun `a turn with no text and no readable photo is dropped entirely`() {
        // An empty content array is rejected by the API outright, so the turn cannot be
        // sent as-is; dropping it keeps the rest of the conversation valid.
        val request = build(
            listOf(
                ChatMessage("1", Role.USER, "real turn"),
                ChatMessage("2", Role.ASSISTANT, "reply"),
                ChatMessage("3", Role.USER, "", listOf(Attachment("b", "missing"))),
            )
        )

        assertEquals(2, request.messages.size)
        assertEquals("assistant", request.messages.last().role)
    }

    @Test
    fun `an attachment on an assistant turn is ignored`() {
        // The assistant cannot send images, and encoding one would put bytes into the
        // prefix that no assistant turn should carry.
        val request = build(
            listOf(
                ChatMessage("1", Role.USER, "hello"),
                ChatMessage("2", Role.ASSISTANT, "reply", listOf(Attachment("a", "/tmp/x.jpg"))),
            )
        )

        val assistant = request.messages.last()
        assertEquals(1, assistant.content.size)
        assertTrue(assistant.content.single() is ContentBlock.Text)
    }

    @Test
    fun `the model and token ceiling are fixed in one place`() {
        val request = build(listOf(ChatMessage("1", Role.USER, "hi")))
        assertEquals("claude-sonnet-5", request.model)
        assertEquals(4096, request.maxTokens)
    }

    @Test
    fun `serialisation settings are fixed, because they change the bytes`() {
        val encoded = AnthropicRequest.json.encodeToString(
            build(listOf(ChatMessage("1", Role.USER, "hi")))
        )

        // encodeDefaults on: the block type must be emitted, since the API needs it.
        assertTrue(encoded, encoded.contains("\"type\":\"text\""))
        // explicitNulls off: an absent cache_control must not appear as a null.
        assertTrue(encoded, !encoded.contains("null"))
        assertNotNull(AnthropicRequest.json)
    }

    @Test
    fun `an empty history still produces a valid request`() {
        // Nothing sends this today, but an empty messages array is an API error and a
        // silent one to introduce.
        val request = build(emptyList())

        assertEquals(1, request.messages.size)
        assertEquals("user", request.messages.single().role)
        val block = request.messages.single().content.single() as ContentBlock.Text
        assertEquals(AnthropicRequest.OPENING_TURN, block.text)
        assertNotNull(block.cacheControl)
    }

    @Test
    fun `a blank system prompt is still sent as a block`() {
        // Falling back to no system block at all would change the request shape, and the
        // prompt repository can legitimately return an empty item protocol.
        val request = build(listOf(ChatMessage("1", Role.USER, "hi")), prompt = "")
        assertEquals("", request.system.single().text)
        assertNull(request.system.single().cacheControl?.ttl?.takeIf { it != "1h" })
    }
}
