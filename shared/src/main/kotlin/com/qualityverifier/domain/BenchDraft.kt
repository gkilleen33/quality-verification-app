package com.qualityverifier.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The tools Claude saw on a fundi's bench (v0.6.0, release scope row 5): a draft the fundi
 * confirms tool by tool, and says who owns each, before anything is saved. What
 * `POST /v1/fundi/bench-photos` answers, and what `bench_photos.draft` keeps (V35).
 *
 * Shared by the server, which parses Claude's reply into it, and the phone, which shows it,
 * so the two read the same list.
 */
@Serializable
data class BenchDraft(val tools: List<BenchTool> = emptyList()) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The draft in a reply: the `qv-bench` block, or failing that the first JSON object
         * in it. Only tool kinds this build knows, each once, in the order given; anything
         * else is dropped rather than refused, as a prompt naming a new kind must not cost
         * the fundi the rest of the list. Null when the reply holds no readable list.
         */
        fun parse(reply: String): BenchDraft? {
            val block = Regex("```qv-bench\\s*(\\{.*?})\\s*```", RegexOption.DOT_MATCHES_ALL)
                .find(reply)?.groupValues?.get(1)
                ?: reply.substringAfter('{', missingDelimiterValue = "").let { rest ->
                    if (rest.isEmpty()) null else "{" + rest.substringBeforeLast('}') + "}"
                }
                ?: return null
            val decoded = runCatching { json.decodeFromString(serializer(), block) }.getOrNull() ?: return null
            val known = decoded.tools
                .mapNotNull { tool -> ToolKind.fromId(tool.kind)?.let { tool.copy(kind = it.id, seen = tool.seen.trim().take(SEEN_LIMIT)) } }
                .distinctBy { it.kind }
            return BenchDraft(known)
        }

        /** A "where I see it" longer than this is cut: it is a hint, not a description. */
        const val SEEN_LIMIT = 120
    }
}

/** One tool on the bench: a [ToolKind] id, and where in the photo Claude saw it. */
@Serializable
data class BenchTool(val kind: String, val seen: String = "")
