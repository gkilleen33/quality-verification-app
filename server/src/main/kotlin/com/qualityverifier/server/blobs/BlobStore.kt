package com.qualityverifier.server.blobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

/**
 * Content-addressed photo storage on the EBS volume.
 *
 * Photos are stored under their SHA-256 rather than a filename, which is what lets the
 * phone stop re-uploading. The app re-sends the whole conversation on every turn, so
 * without this the same nine photos would arrive three or four times per assessment —
 * over a Kampala mobile connection that is the most expensive thing the app does.
 *
 * Two levels of fan-out on the first two hex characters: 256 directories, so no single
 * directory holds the whole corpus. ext4 copes either way, but `ls` on a directory with
 * a hundred thousand entries is the kind of thing that makes an incident worse.
 */
class BlobStore(private val root: File) {

    private val log = LoggerFactory.getLogger(BlobStore::class.java)

    init {
        if (!root.exists() && !root.mkdirs()) {
            log.error("Could not create the blob root at {}", root)
        }
    }

    sealed interface PutResult {
        data object Stored : PutResult
        data object AlreadyPresent : PutResult
        /** The bytes do not hash to the name the client claimed. */
        data class HashMismatch(val actual: String) : PutResult
        data object TooLarge : PutResult
    }

    fun exists(sha256: String): Boolean =
        isValidHash(sha256) && pathFor(sha256).isFile

    fun read(sha256: String): ByteArray? {
        if (!isValidHash(sha256)) return null
        val file = pathFor(sha256)
        return if (file.isFile) runCatching { file.readBytes() }.getOrNull() else null
    }

    /**
     * Stores bytes under a hash the caller claims, **after verifying it**.
     *
     * Verification is not optional. Blobs are global and content-addressed, so a client
     * that could store arbitrary bytes under a hash of its choosing could replace another
     * customer's photograph — and every later request naming that hash would silently get
     * the wrong image. Checking costs one pass over a few hundred kilobytes.
     */
    suspend fun put(sha256: String, bytes: ByteArray): PutResult = withContext(Dispatchers.IO) {
        if (bytes.size > MAX_BYTES) return@withContext PutResult.TooLarge
        val actual = hash(bytes)
        if (!actual.equals(sha256, ignoreCase = true)) {
            log.warn("Rejected a blob: claimed {}, hashed {}", sha256.take(12), actual.take(12))
            return@withContext PutResult.HashMismatch(actual)
        }

        val target = pathFor(actual)
        if (target.isFile) return@withContext PutResult.AlreadyPresent

        target.parentFile?.mkdirs()
        // Written to a temporary file in the same directory and renamed, so a request
        // that dies half-way cannot leave a truncated blob that later reads as valid —
        // the name is the hash, so a partial file would be a lie about its own contents.
        val temp = File(target.parentFile, "${actual}.part")
        temp.writeBytes(bytes)
        if (!temp.renameTo(target)) {
            temp.delete()
            // Losing the race is fine: whoever won wrote the same bytes, by definition.
            if (target.isFile) return@withContext PutResult.AlreadyPresent
            log.error("Could not move a blob into place at {}", target)
            return@withContext PutResult.TooLarge
        }
        PutResult.Stored
    }

    /**
     * Every stored photo, as hash to file.
     *
     * Used by the sweep that enforces retention on disk. Returns the whole corpus in one
     * go, which is right while that is thousands of files on one volume and would want
     * rethinking somewhere north of a few hundred thousand.
     */
    fun all(): List<Pair<String, File>> =
        root.listFiles { file: File -> file.isDirectory }.orEmpty().flatMap { bucket ->
            bucket.listFiles { file: File -> file.isFile && file.name.endsWith(".jpg") }
                .orEmpty()
                .mapNotNull { file ->
                    val hash = file.name.removeSuffix(".jpg")
                    // A file whose name is not a hash was not written by put(); leave it
                    // alone rather than guess at what it is.
                    if (isValidHash(hash)) hash to file else null
                }
        }

    /**
     * Removes a photo. Returns the bytes freed, or 0 if it was not there.
     *
     * Deliberately on the store rather than in the sweep: the two-level fan-out is this
     * class's business, and a caller that built the path itself would be a second place
     * that has to agree about the layout.
     */
    fun delete(sha256: String): Long {
        if (!isValidHash(sha256)) return 0
        val file = pathFor(sha256)
        if (!file.isFile) return 0
        val size = file.length()
        if (!file.delete()) {
            log.warn("Could not delete blob {}", sha256)
            return 0
        }
        // Tidy the fan-out directory once it empties, so the tree does not keep 256
        // empty folders forever. Fails harmlessly if another put() just landed in it.
        file.parentFile?.takeIf { it.list()?.isEmpty() == true }?.delete()
        return size
    }

    fun pathFor(sha256: String): File {
        val lower = sha256.lowercase()
        return File(File(root, lower.substring(0, 2)), "$lower.jpg")
    }

    companion object {
        /** A normalised photo is 150-400KB; 8MB is generous headroom, not a target. */
        const val MAX_BYTES = 8 * 1024 * 1024

        private val HASH = Regex("^[0-9a-fA-F]{64}$")

        /**
         * Rejects anything that is not 64 hex characters, which also stops a path
         * traversal: the hash becomes a directory name, so "../../etc" must never
         * reach the filesystem.
         */
        fun isValidHash(value: String): Boolean = HASH.matches(value)

        fun hash(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
