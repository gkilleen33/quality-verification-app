package com.qualityverifier.data.workers

import com.qualityverifier.data.db.PendingSessionWorkersEntity
import com.qualityverifier.data.db.SessionDao
import com.qualityverifier.data.db.SessionWorkerEntity
import com.qualityverifier.data.db.WorkerEntity
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerSlot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * The manager's roster, and who worked on which part of each piece.
 *
 * Local first, like everything else the phone writes: a worker added in the workshop has
 * to be there before the next sync, and the picker has to work with no signal. Each write
 * is marked unsent and goes up on the next sync, before the pull, so the pull cannot
 * overwrite it. See `AssessmentSync`.
 *
 * Its own interface rather than more methods on SessionRepository, which every fake in
 * two apps would then have to implement for a roster only Fundi Bora has.
 */
interface WorkerRepository {
    /** Every worker, archived ones included, so a past piece can still name them. */
    fun observeWorkers(): Flow<List<Worker>>

    /** Adds a worker with a phone-made id, as sessions and pieces are made. */
    suspend fun add(name: String): Worker

    suspend fun rename(workerId: String, name: String)

    /** Archive, never delete: the name stays on the pieces this person worked on. */
    suspend fun archive(workerId: String)

    /** Workers the server has not been told about, oldest first. */
    suspend fun unsynced(): List<WorkerChange>

    suspend fun markSynced(change: WorkerChange)

    /**
     * The server's roster, applied where this phone holds no unsent change. A worker the
     * phone has never seen is inserted; archiving is sticky in both directions.
     */
    suspend fun mergeFromServer(workers: List<ServerWorker>)

    /** Who worked on an assessment, as recorded on this phone. */
    suspend fun workersOf(sessionId: String): List<WorkerSlot>

    /**
     * Replaces who worked on an assessment and queues the set for the server. Used by
     * the card's "Who worked on this?"; the opening turn carries the first set itself.
     */
    suspend fun setWorkersOf(sessionId: String, workers: List<WorkerSlot>)

    /** Records the first set with the session, already as good as sent: the turn carries it. */
    suspend fun recordWorkersOf(sessionId: String, workers: List<WorkerSlot>)

    /** Sessions whose worker set the server has not been told about, oldest first. */
    suspend fun pendingSessions(): List<PendingSessionSet>

    /** Clears the record, unless the set was edited again after [pending] was read. */
    suspend fun clearPending(pending: PendingSessionSet)

    /** The server's set for a session, applied unless this phone holds an unsent edit. */
    suspend fun applySynced(sessionId: String, workers: List<WorkerSlot>)
}

/** A roster write waiting to reach the server. [asOf] is the phone's clock at the write. */
data class WorkerChange(val id: String, val name: String, val archived: Boolean, val asOf: Long)

/** A roster row as the server sends it. */
data class ServerWorker(val id: String, val name: String?, val archivedAt: Long?)

/** A session whose worker set is unsent. [asOf] is the phone's clock at the edit. */
data class PendingSessionSet(val sessionId: String, val asOf: Long)

class RoomWorkerRepository(
    private val dao: SessionDao,
    private val now: () -> Long = System::currentTimeMillis,
) : WorkerRepository {

    override fun observeWorkers(): Flow<List<Worker>> =
        dao.observeWorkers().map { rows ->
            rows.map { Worker(it.id, it.name.orEmpty(), archived = it.archivedAt != null) }
        }

    override suspend fun add(name: String): Worker {
        val worker = Worker(UUID.randomUUID().toString(), name.trim(), archived = false)
        dao.upsertWorker(
            WorkerEntity(worker.id, worker.name, archivedAt = null, updatedAt = now(), syncedAt = null)
        )
        return worker
    }

    override suspend fun rename(workerId: String, name: String) {
        val existing = dao.findWorker(workerId) ?: return
        dao.upsertWorker(existing.copy(name = name.trim(), updatedAt = later(existing), syncedAt = null))
    }

    override suspend fun archive(workerId: String) {
        val existing = dao.findWorker(workerId) ?: return
        if (existing.archivedAt != null) return
        val at = later(existing)
        dao.upsertWorker(existing.copy(archivedAt = at, updatedAt = at, syncedAt = null))
    }

    /** Strictly after the last write, so the sync's `updatedAt` guard can tell two edits apart. */
    private fun later(existing: WorkerEntity): Long = maxOf(now(), existing.updatedAt + 1)

    override suspend fun unsynced(): List<WorkerChange> =
        dao.unsyncedWorkers().map {
            WorkerChange(it.id, it.name.orEmpty(), archived = it.archivedAt != null, asOf = it.updatedAt)
        }

    override suspend fun markSynced(change: WorkerChange) =
        dao.markWorkerSynced(change.id, change.asOf, now())

    override suspend fun mergeFromServer(workers: List<ServerWorker>) {
        val syncedAt = now()
        for (worker in workers) {
            // Two statements that each decide for themselves, so an edit made between
            // them is kept: the insert is a no-op on a known worker, and the update
            // touches only a row with nothing unsent.
            dao.insertWorkerIfAbsent(
                WorkerEntity(worker.id, worker.name, worker.archivedAt, updatedAt = syncedAt, syncedAt = syncedAt)
            )
            dao.applySyncedWorker(worker.id, worker.name, worker.archivedAt, syncedAt)
        }
    }

    override suspend fun workersOf(sessionId: String): List<WorkerSlot> =
        dao.sessionWorkers(sessionId).map { it.toSlot() }

    override suspend fun setWorkersOf(sessionId: String, workers: List<WorkerSlot>) {
        dao.editSessionWorkers(
            sessionId,
            workers.map { it.toEntity(sessionId) },
            PendingSessionWorkersEntity(sessionId, now()),
        )
    }

    override suspend fun recordWorkersOf(sessionId: String, workers: List<WorkerSlot>) {
        dao.replaceSessionWorkers(sessionId, workers.map { it.toEntity(sessionId) })
    }

    override suspend fun pendingSessions(): List<PendingSessionSet> =
        dao.pendingSessionWorkers().map { PendingSessionSet(it.sessionId, it.requestedAt) }

    override suspend fun clearPending(pending: PendingSessionSet) =
        dao.clearPendingSessionWorkers(pending.sessionId, pending.asOf)

    override suspend fun applySynced(sessionId: String, workers: List<WorkerSlot>) =
        dao.applySessionWorkersUnlessPending(sessionId, workers.map { it.toEntity(sessionId) })

    private fun SessionWorkerEntity.toSlot() =
        WorkerSlot(workerId = workerId, part = part?.let(WorkPart::fromId))

    private fun WorkerSlot.toEntity(sessionId: String) =
        SessionWorkerEntity(sessionId = sessionId, workerId = workerId, part = part?.id)
}
