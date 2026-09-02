package com.qualityverifier.server.blobs

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import javax.sql.DataSource

/**
 * Deletes photo files that no assessment refers to any more.
 *
 * `purge_expired()` removes rows and cannot touch the filesystem, so until this existed the
 * retention windows deleted the record of a photograph and kept the photograph. That made
 * the app's own delete dialog — "then it is deleted for good" — false about the most
 * sensitive thing we hold, and it left the bytes reachable by anybody with disk access or a
 * snapshot long after the row authorising them had gone.
 *
 * The check is **global, not per-session**. Blobs are deduplicated by hash, so two customers
 * who photograph the same thing share one file; deleting the file because *one* assessment
 * went would take a photo another customer still has. So the question asked here is only
 * ever "does any live attachment row point at this hash".
 *
 * That inverts the ordering problem rather than solving it. Deleting the file first and
 * crashing leaves a row pointing at nothing; deleting the row first and crashing leaves a
 * file nobody knows about. Sweeping makes the second case self-correcting — an orphan is
 * found on the next run — which is why there is no tracking table. A tracking table would
 * reproduce the current bug in a new form: a missed write and the file is invisible forever.
 */
class BlobSweeper(
    private val referenced: ReferencedHashes,
    private val blobs: BlobStore,
    /**
     * How long a file must have been on disk before it can be swept.
     *
     * Photos are uploaded before the turn that references them, so a newly stored blob is
     * legitimately unreferenced for as long as it takes the customer to finish and submit
     * — and if the submission fails, until they reopen the assessment and send it again,
     * which may be the next day. Seven days is well past both, and the cost of waiting is
     * a few hundred kilobytes.
     */
    private val grace: Duration = Duration.ofDays(7),
) {
    private val log = LoggerFactory.getLogger(BlobSweeper::class.java)

    data class Result(val deleted: Int, val bytesFreed: Long, val heldByGrace: Int, val kept: Int) {
        override fun toString() =
            "deleted=$deleted freed=${bytesFreed / 1024}KB held=$heldByGrace kept=$kept"
    }

    suspend fun sweep(now: Long = System.currentTimeMillis()): Result = withContext(Dispatchers.IO) {
        val onDisk = blobs.all()
        if (onDisk.isEmpty()) return@withContext Result(0, 0, 0, 0)

        // Normalised here, not just in the Postgres reader. Whether a hash arrives upper
        // or lower case must not decide whether a customer's photo is deleted, and this is
        // the one place that guarantee can be made for every implementation.
        val referenced = referenced.hashes().mapTo(HashSet()) { it.lowercase() }
        // Read once into a set rather than a query per file: one round trip instead of
        // thousands, and the whole point is to compare two complete pictures.
        val cutoff = now - grace.toMillis()

        var deleted = 0
        var freed = 0L
        var held = 0
        var kept = 0
        for ((hash, file) in onDisk) {
            if (hash in referenced) {
                kept++
                continue
            }
            if (file.lastModified() > cutoff) {
                held++
                continue
            }
            val bytes = blobs.delete(hash)
            if (bytes > 0) {
                deleted++
                freed += bytes
            }
        }
        Result(deleted, freed, held, kept).also {
            // Always logged, including a no-op run: "the sweep ran and removed nothing" and
            // "the sweep did not run" look identical afterwards otherwise, and this is the
            // only evidence that a retention promise is being kept.
            log.info("Blob sweep: {}", it)
        }
    }

}

/**
 * Every photo hash some live assessment still points at.
 *
 * An interface so the sweep's decisions can be tested without a database. That is not
 * ceremony: what is worth exercising here is which files get deleted, and a sweep whose
 * rules could only be run against live Postgres is a sweep whose rules are not run at all.
 */
fun interface ReferencedHashes {
    suspend fun hashes(): Set<String>
}

/**
 * Reads them from `attachments`.
 *
 * Deliberately the whole table and not a per-session query: a hash is shared between
 * customers, so anything narrower would answer a different and more dangerous question.
 */
class PostgresReferencedHashes(private val dataSource: DataSource) : ReferencedHashes {
    override suspend fun hashes(): Set<String> = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement("select distinct sha256 from attachments").use { statement ->
                statement.executeQuery().use { rows ->
                    val out = HashSet<String>()
                    while (rows.next()) out += rows.getString(1).lowercase()
                    out
                }
            }
        }
    }
}
