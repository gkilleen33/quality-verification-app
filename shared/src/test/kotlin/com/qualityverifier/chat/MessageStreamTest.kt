package com.qualityverifier.chat

import com.qualityverifier.data.chat.MessageStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reassembling a streamed reply.
 *
 * Fed line by line, as the socket delivers it, because the failures worth pinning are all
 * about incompleteness: a stream that stops mid-sentence, usage split across two events,
 * an error after a hundred good deltas. None of those are reachable through a happy-path
 * fixture, and all of them decide whether a customer's turn is stored or discarded.
 */
class MessageStreamTest {

    private fun MessageStream.feed(vararg lines: String): List<String> =
        lines.mapNotNull { accept(it) }

    @Test
    fun `text deltas accumulate in order`() {
        val stream = MessageStream()
        val deltas = stream.feed(
            """data: {"type":"message_start","message":{"model":"claude-sonnet-5","usage":{"input_tokens":2}}}""",
            """data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"This "}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"table "}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"wobbles."}}""",
            """data: {"type":"content_block_stop","index":0}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":12}}""",
            """data: {"type":"message_stop"}""",
        )

        // Increments, never the running total: the caller forwards these and must not
        // have to work out what is new.
        assertEquals(listOf("This ", "table ", "wobbles."), deltas)
        assertEquals("This table wobbles.", stream.text)
        assertTrue(stream.isComplete)
        assertEquals("claude-sonnet-5", stream.model)
        assertEquals("end_turn", stream.stopReason)
    }

    @Test
    fun `usage is collected from both events that carry it`() {
        // input and cache counts arrive at the start, output tokens only at the end. A
        // reader that took either event alone would under-report the bill.
        val stream = MessageStream()
        stream.feed(
            """data: {"type":"message_start","message":{"usage":{"input_tokens":2,"cache_read_input_tokens":8340,"cache_creation_input_tokens":11}}}""",
            """data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1983}}""",
            """data: {"type":"message_stop"}""",
        )

        assertEquals(2, stream.inputTokens)
        assertEquals(8340, stream.cacheReadTokens)
        assertEquals(11, stream.cacheCreationTokens)
        assertEquals(1983, stream.outputTokens)
    }

    @Test
    fun `a second event without a field does not zero what was already read`() {
        // message_delta reports output tokens and repeats nothing else. Overwriting the
        // cache counts with absent-means-zero would make prompt caching look broken,
        // which is the one signal the usage log exists to give.
        val stream = MessageStream()
        stream.feed(
            """data: {"type":"message_start","message":{"usage":{"input_tokens":2,"cache_read_input_tokens":8340}}}""",
            """data: {"type":"message_delta","usage":{"output_tokens":40}}""",
        )

        assertEquals(8340, stream.cacheReadTokens)
        assertEquals(2, stream.inputTokens)
        assertEquals(40, stream.outputTokens)
    }

    @Test
    fun `a stream that stops early is not complete`() {
        val stream = MessageStream()
        stream.feed(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"The back leg"}}""",
        )

        // Text arrived, and it is still a truncated answer. The distinction is the whole
        // reason isComplete exists: the route discards a reply that never finished.
        assertEquals("The back leg", stream.text)
        assertFalse(stream.isComplete)
        assertNull(stream.errorMessage)
    }

    @Test
    fun `an error event is reported even after good deltas`() {
        val stream = MessageStream()
        stream.feed(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Looking"}}""",
            """data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""",
        )

        assertNotNull(stream.errorMessage)
        assertEquals("Overloaded", stream.errorMessage)
        assertFalse(stream.isComplete)
    }

    @Test
    fun `only text deltas become text`() {
        // A thinking delta or a tool-input delta is not the reply. Appending one would
        // put the model's working out in the customer's chat bubble.
        val stream = MessageStream()
        val deltas = stream.feed(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"hmm"}}""",
            """data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
            """data: {"type":"content_block_delta","index":2,"delta":{"type":"text_delta","text":"Right."}}""",
        )

        assertEquals(listOf("Right."), deltas)
        assertEquals("Right.", stream.text)
    }

    @Test
    fun `noise between events is ignored`() {
        val stream = MessageStream()
        val deltas = stream.feed(
            "event: content_block_delta",
            "",
            ": this is a comment",
            """data: {"type":"ping"}""",
            "data: ",
            "data: [DONE]",
            "not an sse line at all",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Fine."}}""",
        )

        // The event: line is deliberately not trusted — the payload's own type is the
        // single source of truth, so the two can never disagree.
        assertEquals(listOf("Fine."), deltas)
    }

    @Test
    fun `an unparseable payload is skipped rather than throwing`() {
        // A truncated final line is what a dropped connection looks like. It must not
        // take the whole reply down with it.
        val stream = MessageStream()
        val deltas = stream.feed(
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Good"}}""",
            """data: {"type":"content_block_de""",
        )

        assertEquals(listOf("Good"), deltas)
        assertEquals("Good", stream.text)
    }

    @Test
    fun `an unknown event type is ignored`() {
        // The event set is versioned upstream and grows. A stream carrying something we
        // have never heard of has to keep working.
        val stream = MessageStream()
        stream.feed(
            """data: {"type":"something_new_in_2027","payload":{"whatever":true}}""",
            """data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Still fine."}}""",
            """data: {"type":"message_stop"}""",
        )

        assertEquals("Still fine.", stream.text)
        assertTrue(stream.isComplete)
    }

    @Test
    fun `a data line without the conventional space still parses`() {
        val stream = MessageStream()
        val deltas = stream.feed(
            """data:{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Tight."}}""",
        )

        assertEquals(listOf("Tight."), deltas)
    }
}
