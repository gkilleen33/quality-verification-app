package com.qualityverifier.server

import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.chat.AnthropicClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.Compaction
import com.qualityverifier.server.chat.PhotoShelf
import com.qualityverifier.server.db.Digest
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Follow-ups after an assessment: the model's notes in place of the photographs, and the
 * tool that brings a photograph back when a question needs it.
 */
class CompactionTest {

    private fun photo(n: Int) = Attachment("a$n", "/blobs/hash$n.jpg")

    /** Opening with one photo, a plan, seven photos back, the verdict, then a question. */
    private val history = listOf(
        ChatMessage("u1", Role.USER, "I am buying this table.", listOf(photo(1))),
        ChatMessage("a1", Role.ASSISTANT, "Here is the plan."),
        ChatMessage("u2", Role.USER, "7 of 7 photos taken", (2..8).map(::photo)),
        ChatMessage("a2", Role.ASSISTANT, "```qv-verdict\n{}\n```"),
        ChatMessage("u3", Role.USER, "Will the wobble get worse?"),
    )

    @Test
    fun `with no notes the conversation goes as it is and no tool is offered`() {
        val view = Compaction.forModel(history, emptyList())
        assertEquals(history, view.messages)
        assertNull(view.photos)
    }

    @Test
    fun `notes replace everything before the reply they cover`() {
        val view = Compaction.forModel(history, listOf(Digest("a2", "Photo 3 shows the joint.", 8)))

        assertEquals(listOf("notes-a2", "a2", "u3"), view.messages.map { it.id })
        val notes = view.messages.first()
        assertEquals(Role.USER, notes.role)
        assertTrue(notes.text.startsWith(Compaction.NOTES_HEADER))
        assertTrue(notes.text.endsWith("Photo 3 shows the joint."))
        // The point of it: no photograph travels with the turn any more.
        assertTrue(view.messages.all { it.attachments.isEmpty() })
        assertEquals(8, view.photos!!.size)
    }

    @Test
    fun `notes whose photo count disagrees are not used`() {
        // They number photographs, and a different count means the numbers point at the
        // wrong ones. The full conversation is the honest fallback.
        val view = Compaction.forModel(history, listOf(Digest("a2", "notes", 5)))
        assertEquals(history, view.messages)
        assertNull(view.photos)
    }

    @Test
    fun `the newest notes that fit win`() {
        val longer = history + listOf(
            ChatMessage("a3", Role.ASSISTANT, "Corrected verdict."),
            ChatMessage("u4", Role.USER, "Thanks"),
        )
        val view = Compaction.forModel(
            longer,
            listOf(Digest("a3", "newer", 8), Digest("a2", "older", 8)),
        )
        assertEquals(listOf("notes-a3", "a3", "u4"), view.messages.map { it.id })
    }

    @Test
    fun `notes for a reply that is not in the conversation are skipped`() {
        val view = Compaction.forModel(history, listOf(Digest("gone", "notes", 8)))
        assertEquals(history, view.messages)
    }

    @Test
    fun `the request numbers photographs the way the shelf will`() {
        val covered = history.take(4)
        val request = Compaction.requestTurn("Write notes.", covered, Compaction.forModel(covered, emptyList()))

        assertEquals(Role.USER, request.role)
        assertTrue(request.text.startsWith("Write notes.\n"))
        assertTrue(request.text.contains("- Photo 1: attached to the message that begins \"I am buying this table.\"."))
        assertTrue(request.text.contains("- Photos 2 to 8: attached to the message that begins \"7 of 7 photos taken\"."))
        assertFalse(request.text.contains("not attached now"))
    }

    @Test
    fun `a second set of notes says which photographs are only in the first`() {
        val longer = history + ChatMessage("a3", Role.ASSISTANT, "Corrected verdict.")
        val view = Compaction.forModel(longer, listOf(Digest("a2", "older", 8)))
        val request = Compaction.requestTurn("Write notes.", longer, view)
        assertTrue(request.text.contains("Photo 1: attached to the message that begins \"I am buying this table.\" (described in your earlier notes; not attached now)."))
    }

    @Test
    fun `only an assessment with photographs is worth compacting`() {
        assertTrue(Compaction.worthCompacting(hasVerdict = true, hasDiagnosis = false, history))
        assertTrue(Compaction.worthCompacting(hasVerdict = false, hasDiagnosis = true, history))
        assertFalse(Compaction.worthCompacting(hasVerdict = false, hasDiagnosis = false, history))
        val noPhotos = history.map { it.copy(attachments = emptyList()) }
        assertFalse(Compaction.worthCompacting(hasVerdict = true, hasDiagnosis = false, noPhotos))
    }

    @Test
    fun `the shelf resolves numbers and names the ones that do not exist`() {
        val shelf = PhotoShelf((1..3).map(::photo))
        val lookup = shelf.resolve(JsonObject(mapOf("photos" to JsonArray(listOf(JsonPrimitive(2), JsonPrimitive(9))))))
        assertEquals(listOf(2), lookup.found.map { it.first })
        assertEquals("a2", lookup.found.single().second.id)
        assertTrue(lookup.problem!!.contains("no photo 9"))
    }

    @Test
    fun `the shelf shows at most four at a time`() {
        val shelf = PhotoShelf((1..8).map(::photo))
        val lookup = shelf.resolve(JsonObject(mapOf("photos" to JsonArray((1..6).map { JsonPrimitive(it) }))))
        assertEquals(listOf(1, 2, 3, 4), lookup.found.map { it.first })
        assertTrue(lookup.problem!!.contains("Only 4"))
    }

    // ------------------------------------------------------------ the client's tool loop

    /** Answers each request with the next canned body, and keeps what was sent. */
    private class Scripted(private val bodies: List<String>) : Interceptor {
        val sent = mutableListOf<String>()
        override fun intercept(chain: Interceptor.Chain): Response {
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            sent += buffer.readUtf8()
            val body = bodies[sent.size - 1]
            return Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
    }

    private fun client(script: Scripted) = AnthropicClient(
        client = OkHttpClient.Builder().addInterceptor(script).build(),
        apiKey = { "test-key" },
        baseUrl = "http://anthropic.invalid/v1/messages",
    )

    private val toolCall = """
        {"model":"claude-sonnet-5-5","stop_reason":"tool_use",
         "content":[{"type":"text","text":"Let me look."},
                    {"type":"tool_use","id":"tu_1","name":"view_photos","input":{"photos":[3]}}],
         "usage":{"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":1000}}
    """.trimIndent()

    private val answer = """
        {"model":"claude-sonnet-5-5","stop_reason":"end_turn",
         "content":[{"type":"text","text":"The crack is on the left leg."}],
         "usage":{"input_tokens":5,"output_tokens":12,"cache_read_input_tokens":1500}}
    """.trimIndent()

    @Test
    fun `a photo lookup is answered with the photograph and the request goes again`() = runBlocking {
        val script = Scripted(listOf(toolCall, answer))
        val view = Compaction.forModel(history, listOf(Digest("a2", "notes", 8)))

        val result = client(script).send("SYSTEM", view.messages, { "jpeg-bytes".toByteArray() }, view.photos)

        result as ClaudeResult.Success
        // Only the final answer is kept: the next turn must not carry the lookup.
        assertEquals("The crack is on the left leg.", result.text)
        assertEquals(32, result.usage.outputTokens)
        assertEquals(2500, result.usage.cacheReadTokens)

        assertEquals(2, script.sent.size)
        val first = Json.parseToJsonElement(script.sent[0]).jsonObject
        assertEquals("view_photos", first["tools"]!!.jsonArray.single().jsonObject["name"]!!.jsonPrimitive.content)

        val second = Json.parseToJsonElement(script.sent[1]).jsonObject
        val messages = second["messages"]!!.jsonArray
        val replayed = messages[messages.size - 2].jsonObject
        assertEquals("assistant", replayed["role"]!!.jsonPrimitive.content)
        assertEquals("tool_use", replayed["content"]!!.jsonArray.last().jsonObject["type"]!!.jsonPrimitive.content)
        val result1 = messages.last().jsonObject["content"]!!.jsonArray.single().jsonObject
        assertEquals("tool_result", result1["type"]!!.jsonPrimitive.content)
        assertEquals("tu_1", result1["tool_use_id"]!!.jsonPrimitive.content)
        val shown = result1["content"]!!.jsonArray.map { it.jsonObject["type"]!!.jsonPrimitive.content }
        assertEquals(listOf("text", "image"), shown)
        assertEquals("Photo 3", result1["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an uncompacted conversation is offered no tool`() = runBlocking {
        val script = Scripted(listOf(answer))
        client(script).send("SYSTEM", history, { "jpeg".toByteArray() }, photos = null)
        val sent = Json.parseToJsonElement(script.sent.single()).jsonObject
        assertFalse(sent.containsKey("tools"))
    }

    @Test
    fun `lookups stop after two and the model is told to answer`() = runBlocking {
        val script = Scripted(listOf(toolCall, toolCall, toolCall, answer))
        val view = Compaction.forModel(history, listOf(Digest("a2", "notes", 8)))

        val result = client(script).send("SYSTEM", view.messages, { "jpeg".toByteArray() }, view.photos)

        assertTrue(result is ClaudeResult.Success)
        assertEquals(4, script.sent.size)
        val last = Json.parseToJsonElement(script.sent[3]).jsonObject["messages"]!!.jsonArray.last().jsonObject
        val refusal = last["content"]!!.jsonArray.single().jsonObject
        assertEquals(true, refusal["is_error"]!!.jsonPrimitive.content.toBoolean())
    }
}
