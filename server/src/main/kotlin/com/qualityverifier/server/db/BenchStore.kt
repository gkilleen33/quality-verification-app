package com.qualityverifier.server.db

import com.qualityverifier.server.routes.SessionLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.Types
import javax.sql.DataSource

/**
 * Bench photos at setup (V35, release scope row 5): one row per photo, its GPS point when the
 * fundi allows location, and the draft Claude made from it.
 *
 * Its own store, as the reports have theirs: a bench photo belongs to no assessment, and the
 * chat store's fakes would otherwise grow methods they never call.
 */
interface BenchStore {
    /**
     * Records a bench photo, unless this account has reached [dailyLimit] today (Nairobi
     * day), or finds the one already recorded with this id. A photo id is the phone's, so a
     * retry after a lost answer is the same row and is never counted twice.
     */
    suspend fun record(
        userId: String,
        photoId: String,
        sha256: String,
        location: SessionLocation?,
        dailyLimit: Int,
    ): BenchRecord

    /** Writes Claude's draft, as the JSON it was parsed into, with the prompt and model that made it. */
    suspend fun saveDraft(photoId: String, draftJson: String, promptSha: String, model: String?)
}

sealed interface BenchRecord {
    /** Newly recorded, or recorded before by this account; [BenchPhoto.draftJson] says which needs a call. */
    data class Ready(val photo: BenchPhoto) : BenchRecord
    /** The id is another account's: the route answers 404, as for a stranger's piece. */
    data object NotYours : BenchRecord
    data class LimitReached(val limit: Int) : BenchRecord
}

/** A bench photo as stored. [draftJson] is null until Claude has answered. */
data class BenchPhoto(val id: String, val sha256: String, val draftJson: String?)

class PostgresBenchStore(private val dataSource: DataSource) : BenchStore {

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                block(connection).also { connection.commit() }
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun record(
        userId: String,
        photoId: String,
        sha256: String,
        location: SessionLocation?,
        dailyLimit: Int,
    ): BenchRecord = tx { connection ->
        // One bench photo at a time per account, for the rest of this transaction, as new
        // assessments are serialised (ChatStore.ensureSession): two photos sent together
        // would otherwise both read the same count and both pass the limit.
        connection.prepareStatement("select pg_advisory_xact_lock(hashtext(?))").use {
            it.setString(1, "bench:$userId")
            it.execute()
        }
        existing(connection, photoId)?.let { (owner, photo) ->
            return@tx if (owner == userId) BenchRecord.Ready(photo) else BenchRecord.NotYours
        }
        val today = connection.prepareStatement(
            """
            select count(*)::int from bench_photos
             where user_id = ?::uuid
               and (taken_at at time zone 'Africa/Nairobi')::date = (now() at time zone 'Africa/Nairobi')::date
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows -> if (rows.next()) rows.getInt(1) else 0 }
        }
        if (today >= dailyLimit) return@tx BenchRecord.LimitReached(dailyLimit)
        connection.prepareStatement(
            """
            insert into bench_photos (id, user_id, sha256, location, location_accuracy_m, location_at)
            values (?::uuid, ?::uuid, ?,
                    case when ? then ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography end,
                    ?, case when ? then now() end)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, photoId)
            statement.setString(2, userId)
            statement.setString(3, sha256.lowercase())
            statement.setBoolean(4, location != null)
            // Longitude first: ST_MakePoint takes x then y.
            statement.setDouble(5, location?.longitude ?: 0.0)
            statement.setDouble(6, location?.latitude ?: 0.0)
            if (location == null) statement.setNull(7, Types.REAL) else statement.setDouble(7, location.accuracyMetres)
            statement.setBoolean(8, location != null)
            statement.executeUpdate()
        }
        BenchRecord.Ready(BenchPhoto(photoId, sha256.lowercase(), draftJson = null))
    }

    /** The row with this id and its owner, or null. */
    private fun existing(connection: Connection, photoId: String): Pair<String, BenchPhoto>? =
        connection.prepareStatement(
            "select user_id::text, id::text, sha256, draft::text from bench_photos where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, photoId)
            statement.executeQuery().use { rows ->
                if (rows.next()) rows.getString(1) to BenchPhoto(rows.getString(2), rows.getString(3).trim(), rows.getString(4))
                else null
            }
        }

    override suspend fun saveDraft(photoId: String, draftJson: String, promptSha: String, model: String?) = tx { connection ->
        connection.prepareStatement(
            "update bench_photos set draft = ?::jsonb, prompt_sha = ?, model = ? where id = ?::uuid"
        ).use { statement ->
            statement.setString(1, draftJson)
            statement.setString(2, promptSha)
            statement.setString(3, model)
            statement.setString(4, photoId)
            statement.executeUpdate()
        }
        Unit
    }
}
