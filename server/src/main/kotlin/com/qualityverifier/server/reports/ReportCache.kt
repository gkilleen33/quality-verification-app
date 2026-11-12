package com.qualityverifier.server.reports

import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest
import java.time.Duration

/**
 * Rendered reports kept on disk for a day (plan v7 §5.4), so a retry after a failed
 * download costs a transfer and no render.
 *
 * On disk beside the blob store, not inside it: BlobStore treats every folder under its
 * root as a hash bucket. Not in memory: several 500 KB files would compete with renders for
 * the 640 MB heap and be lost on restart. Each file is named by the hash of the report's
 * inputs, so two reports of one piece never remove each other, and an hourly sweep deletes
 * files older than a day. Nothing else deletes them.
 */
class ReportCache(
    private val root: File,
    private val ttl: Duration = Duration.ofHours(24),
) {
    private val log = LoggerFactory.getLogger(ReportCache::class.java)

    init {
        if (!root.exists() && !root.mkdirs()) log.error("Could not create the report cache at {}", root)
    }

    fun get(key: String): ByteArray? {
        val file = fileFor(key) ?: return null
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    fun put(key: String, bytes: ByteArray) {
        val file = fileFor(key) ?: return
        // Written beside and renamed, as a blob is, so a crash half way cannot leave a
        // truncated file that later reads as a whole report.
        val temp = File(root, "${file.name}.part")
        runCatching {
            temp.writeBytes(bytes)
            if (!temp.renameTo(file)) {
                temp.delete()
                if (!file.isFile) log.warn("Could not move a report into the cache at {}", file)
            }
        }.onFailure { log.warn("Could not cache a report", it) }
    }

    /** Deletes every file older than the ttl. Returns how many went. */
    fun sweep(now: Long = System.currentTimeMillis()): Int {
        val cutoff = now - ttl.toMillis()
        var deleted = 0
        root.listFiles { file: File -> file.isFile }.orEmpty().forEach { file ->
            if (file.lastModified() < cutoff && file.delete()) deleted++
        }
        // Logged on every run, as the blob sweep is: "ran and removed nothing" and "did not
        // run" look the same afterwards otherwise.
        log.info("Report cache sweep: deleted={}", deleted)
        return deleted
    }

    /** The key is a hash, so it is the filename; anything else is refused rather than used as a path. */
    private fun fileFor(key: String): File? =
        if (HASH.matches(key)) File(root, "$key.pdf") else null

    companion object {
        private val HASH = Regex("^[0-9a-f]{64}$")

        /** The key for a set of inputs: SHA-256 over them, each on its own line. */
        fun keyOf(inputs: List<String>): String =
            sha256(inputs.joinToString("\n").toByteArray(Charsets.UTF_8))

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
