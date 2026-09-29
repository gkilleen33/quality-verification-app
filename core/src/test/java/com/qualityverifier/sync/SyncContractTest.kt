package com.qualityverifier.sync

import com.qualityverifier.data.sync.RemoteSessionDetail
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's half of the history sync contract, for the two fields a reinstall depends on.
 *
 * The server's `SyncRouteTest` asserts it sends `piece_id` and `composed`; this asserts the
 * phone reads those exact names. A mismatch fails in neither module on its own — an unknown
 * key is ignored and the field decodes to its default — so the grouping and the compact
 * intake would silently vanish after the next reinstall.
 */
class SyncContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val body = """
        {"session": {"id": "s1", "item_type_id": "wooden-stool",
                     "created_at": 1, "updated_at": 2, "preview": "p", "message_count": 2,
                     "intake_answers": "fundi-en-evaluate",
                     "piece_id": "c0ffee00-0000-4000-8000-000000000001"},
         "messages": [
           {"id": "m1", "role": "USER", "text": "About my workshop", "ordinal": 0,
            "created_at": 1, "blobs": [], "composed": true},
           {"id": "m2", "role": "USER", "text": "It was cut freehand", "ordinal": 1,
            "created_at": 2, "blobs": []}
         ]}
    """.trimIndent()

    @Test
    fun `the piece comes back from the server`() {
        val detail = json.decodeFromString<RemoteSessionDetail>(body)

        assertEquals("c0ffee00-0000-4000-8000-000000000001", detail.session.pieceId)
    }

    @Test
    fun `which turns the app wrote comes back too`() {
        val detail = json.decodeFromString<RemoteSessionDetail>(body)

        assertTrue(detail.messages[0].composed)
        // Absent means typed, which is what every turn from before V18 is.
        assertFalse(detail.messages[1].composed)
    }

    @Test
    fun `a session with no piece is still readable`() {
        val detail = json.decodeFromString<RemoteSessionDetail>(
            body.replace(Regex(""",\s*"piece_id":\s*"[^"]*""""), "")
        )

        assertNull(detail.session.pieceId)
    }
}
