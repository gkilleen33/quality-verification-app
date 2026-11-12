package com.qualityverifier.data.workers

import com.qualityverifier.data.db.SessionDao
import com.qualityverifier.data.db.StudioEntity
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.StudioDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The studios this account says it works at (V31): the other side of the roster.
 *
 * Local first, as the roster is: an entry added in the workshop is there before the next
 * sync, and the list works with no signal. Each write is marked unsent and goes up on the
 * next sync, before the pull, so the pull cannot overwrite it. "Linked" is the server's
 * two-sided match, read at each pull and kept so the mark shows offline; the phone never
 * computes it, since it cannot see the other side.
 */
interface StudioRepository {
    /** Every entry, archived ones included. */
    fun observeStudios(): Flow<List<Studio>>

    /** Adds an entry with a phone-made id, as roster entries are made. */
    suspend fun add(details: StudioDetails): Studio

    /** The name and number together, as the form edits them. */
    suspend fun edit(studioId: String, details: StudioDetails)

    /** Archive, never delete: a past link stays on record. */
    suspend fun archive(studioId: String)

    /** Entries the server has not been told about, oldest first. */
    suspend fun unsynced(): List<StudioChange>

    suspend fun markSynced(change: StudioChange)

    /**
     * The server's list, applied where this phone holds no unsent change. An entry the
     * phone has never seen is inserted; archiving is sticky in both directions.
     */
    suspend fun mergeFromServer(studios: List<ServerStudio>)
}

/** A studio write waiting to reach the server. [asOf] is the phone's clock at the write. */
data class StudioChange(val id: String, val name: String, val phone: String, val archived: Boolean, val asOf: Long)

/** A studio entry as the server sends it. */
data class ServerStudio(val id: String, val name: String?, val phone: String?, val archivedAt: Long?, val linked: Boolean)

class RoomStudioRepository(
    private val dao: SessionDao,
    private val now: () -> Long = System::currentTimeMillis,
) : StudioRepository {

    override fun observeStudios(): Flow<List<Studio>> =
        dao.observeStudios().map { rows -> rows.map { it.toStudio() } }

    override suspend fun add(details: StudioDetails): Studio {
        val entity = StudioEntity(
            id = UUID.randomUUID().toString(),
            name = details.name.trim(),
            phone = details.phone,
            archivedAt = null,
            updatedAt = now(),
            syncedAt = null,
        )
        dao.upsertStudio(entity)
        return entity.toStudio()
    }

    override suspend fun edit(studioId: String, details: StudioDetails) {
        val existing = dao.findStudio(studioId) ?: return
        dao.upsertStudio(
            existing.copy(
                name = details.name.trim(),
                phone = details.phone,
                updatedAt = later(existing),
                syncedAt = null,
            ),
        )
    }

    override suspend fun archive(studioId: String) {
        val existing = dao.findStudio(studioId) ?: return
        if (existing.archivedAt != null) return
        val at = later(existing)
        // The mark goes with the entry: an archived entry is never linked.
        dao.upsertStudio(existing.copy(archivedAt = at, updatedAt = at, syncedAt = null, linked = false))
    }

    /** Strictly after the last write, so the sync's `updatedAt` guard can tell two edits apart. */
    private fun later(existing: StudioEntity): Long = maxOf(now(), existing.updatedAt + 1)

    override suspend fun unsynced(): List<StudioChange> =
        dao.unsyncedStudios().map {
            StudioChange(it.id, it.name.orEmpty(), it.phone.orEmpty(), archived = it.archivedAt != null, asOf = it.updatedAt)
        }

    override suspend fun markSynced(change: StudioChange) =
        dao.markStudioSynced(change.id, change.asOf, now())

    override suspend fun mergeFromServer(studios: List<ServerStudio>) {
        val syncedAt = now()
        for (studio in studios) {
            // Two statements that each decide for themselves, so an edit made between
            // them is kept: the insert is a no-op on a known entry, and the update
            // touches only a row with nothing unsent.
            dao.insertStudioIfAbsent(
                StudioEntity(
                    studio.id, studio.name, studio.phone, studio.archivedAt,
                    updatedAt = syncedAt, syncedAt = syncedAt, linked = studio.linked,
                ),
            )
            dao.applySyncedStudio(studio.id, studio.name, studio.phone, studio.linked, studio.archivedAt, syncedAt)
        }
    }

    private fun StudioEntity.toStudio() =
        Studio(id, name.orEmpty(), phone.orEmpty(), archived = archivedAt != null, linked = linked)
}
