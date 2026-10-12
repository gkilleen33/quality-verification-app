package com.qualityverifier.server.chat

import com.qualityverifier.data.chat.dto.ToolDefinition
import com.qualityverifier.domain.Attachment
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Every photograph in an assessment, by the number its notes use.
 *
 * Once a conversation is compacted its photographs stop travelling with every turn — that
 * is most of what compaction saves — but a follow-up question can still turn on a detail
 * the notes did not keep. "Is that crack on the left leg or the right?" This is how the
 * model looks again: it names the numbers, and the server attaches those photographs to
 * the one request that needs them. Nothing about the lookup is stored, so the next turn is
 * back to the notes alone.
 *
 * Numbered 1 upwards in the order the photographs were attached across the whole
 * conversation, compacted or not, so the numbers in the notes never move.
 */
class PhotoShelf(private val photos: List<Attachment>) {

    val size: Int get() = photos.size

    /**
     * What the model asked for, resolved. Unknown numbers are named in [problem] rather
     * than dropped silently, so the model is told it asked for a photograph that does not
     * exist instead of believing it is looking at one.
     */
    data class Lookup(val found: List<Pair<Int, Attachment>>, val problem: String?)

    fun resolve(input: JsonObject): Lookup {
        val asked = (input["photos"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.contentOrNull?.toIntOrNull() } }
            .distinct()
        if (asked.isEmpty()) return Lookup(emptyList(), "No photo numbers were given.")
        val wanted = asked.take(MAX_PER_CALL)
        val found = wanted.mapNotNull { n -> photos.getOrNull(n - 1)?.let { n to it } }
        val missing = wanted.filter { n -> photos.getOrNull(n - 1) == null }
        val problems = buildList {
            if (missing.isNotEmpty()) {
                add("There is no photo ${missing.joinToString(", ")}; photos are numbered 1 to $size.")
            }
            if (asked.size > MAX_PER_CALL) {
                add("Only $MAX_PER_CALL photos are shown at a time; ask again for the rest.")
            }
        }
        return Lookup(found, problems.joinToString(" ").ifBlank { null })
    }

    companion object {
        const val TOOL_NAME = "view_photos"

        /** Each photo is about 2,500 tokens; four is a closer look, not the whole set again. */
        const val MAX_PER_CALL = 4

        val TOOL = ToolDefinition(
            name = TOOL_NAME,
            description = "Look again at photographs from earlier in this assessment, by the " +
                "numbers used in your notes. Use it only when answering needs a detail your " +
                "notes do not settle; most questions do not. At most $MAX_PER_CALL photos at a time.",
            inputSchema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("photos") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "integer") }
                        put("description", "Photo numbers, as listed in your notes.")
                    }
                }
                putJsonArray("required") { add(JsonPrimitive("photos")) }
            },
        )
    }
}
