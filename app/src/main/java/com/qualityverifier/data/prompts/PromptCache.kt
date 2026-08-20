package com.qualityverifier.data.prompts

import java.io.File

/**
 * On-disk cache of fetched prompt files.
 *
 * Freshness comes from the file's own modification time, so there is no sidecar
 * metadata to keep in sync. [now] is injectable so TTL behaviour is testable without
 * waiting 24 hours.
 */
class PromptCache(
    private val dir: File,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Cached text plus how old it is. */
    data class Entry(val text: String, val ageMillis: Long)

    fun read(remotePath: String): Entry? {
        val file = fileFor(remotePath)
        if (!file.isFile) return null
        return runCatching {
            Entry(text = file.readText(), ageMillis = (now() - file.lastModified()).coerceAtLeast(0))
        }.getOrNull()
    }

    fun write(remotePath: String, text: String) {
        runCatching {
            dir.mkdirs()
            val file = fileFor(remotePath)
            file.writeText(text)
            file.setLastModified(now())
        }
    }

    fun clear() {
        runCatching { dir.deleteRecursively() }
    }

    /** `items/wooden-table.txt` -> `items_wooden-table.txt`, keeping the cache flat. */
    private fun fileFor(remotePath: String): File =
        File(dir, remotePath.replace('/', '_'))
}
