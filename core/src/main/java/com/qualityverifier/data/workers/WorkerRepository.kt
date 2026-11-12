package com.qualityverifier.data.workers

import com.qualityverifier.data.db.PendingSessionWorkersEntity
import com.qualityverifier.data.db.SessionDao
import com.qualityverifier.data.db.SessionWorkerEntity
import com.qualityverifier.data.db.WorkerEntity
import com.qualityverifier.domain.Employment
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.Worker
import com.qualityverifier.domain.WorkerDetails
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
    suspend fun add(details: WorkerDetails): Worker

    /** The name, number and kind, replaced together: the form edits them as one. */
    suspend fun edit(workerId: String, details: WorkerDetails)

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
     * Every assessment this worker is named on, with the part, as recorded on this phone,
     * re-emitted on each change. The worker's page reads it; the pieces come from the
     * session summaries.
     */
    fun observeNamedOn(workerId: String): Flow<List<WorkerNaming>>

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

/**
 * A roster write waiting to reach the server. [asOf] is the phone's clock at the write.
 * [phone] is sent on every write, null as blank, so a number removed here is removed there.
 */
data class WorkerChange(
    val id: String,
    val name: String,
    val archived: Boolean,
    val asOf: Long,
    val phone: String? = null,
    val employment: Employment = Employment.EMPLOYEE,
)

/**
 * A roster row as the server sends it.
 *
 * [employment] null means the server predates V30 and sent neither a kind nor a number:
 * a V30 server always sends the kind. The phone then keeps its own number and kind for
 * the row, since the server has no opinion, rather than reading the absence as "none" and
 * clearing a number the manager just added. A V30 server's null [phone] is a number
 * removed on another phone, and is applied.
 */
data class ServerWorker(
    val id: String,
    val name: String?,
    val archivedAt: Long?,
    val phone: String? = null,
    val employment: Employment? = null,
)

/** A session whose worker set is unsent. [asOf] is the phone's clock at the edit. */
data class PendingSessionSet(val sessionId: String, val asOf: Long)

/** One "who worked on this" row for a worker: the assessment, and the part or null for the whole piece. */
data class WorkerNaming(val sessionId: String, val part: WorkPart?)

class RoomWorkerRepository(
    private val dao: SessionDao,
    private val now: () -> Long = System::currentTimeMillis,
) : WorkerRepository {

    override fun observeWorkers(): Flow<List<Worker>> =
        dao.observeWorkers().map { rows -> rows.map { it.toWorker() } }

    override suspend fun add(details: WorkerDetails): Worker {
        val entity = WorkerEntity(
            id = UUID.randomUUID().toString(),
            name = details.name.trim(),
            archivedAt = null,
            updatedAt = now(),
            syncedAt = null,
            phone = details.phone,
            employment = details.employment.id,
        )
        dao.upsertWorker(entity)
        return entity.toWorker()
    }

    override suspend fun edit(workerId: String, details: WorkerDetails) {
        val existing = dao.findWorker(workerId) ?: return
        dao.upsertWorker(
            existing.copy(
                name = details.name.trim(),
                phone = details.phone,
                employment = details.employment.id,
                updatedAt = later(existing),
                syncedAt = null,
            ),
        )
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
            WorkerChange(
                it.id, it.name.orEmpty(), archived = it.archivedAt != null, asOf = it.updatedAt,
                phone = it.phone, employment = it.employmentOrDefault(),
            )
        }

    override suspend fun markSynced(change: WorkerChange) =
        dao.markWorkerSynced(change.id, change.asOf, now())

    override suspend fun mergeFromServer(workers: List<ServerWorker>) {
        val syncedAt = now()
        for (worker in workers) {
            // Two statements that each decide for themselves, so an edit made between
            // them is kept: the insert is a no-op on a known worker, and the update
            // touches only a row with nothing unsent.
            val employment = worker.employment
            dao.insertWorkerIfAbsent(
                WorkerEntity(
                    worker.id, worker.name, worker.archivedAt, updatedAt = syncedAt, syncedAt = syncedAt,
                    phone = worker.phone, employment = (employment ?: Employment.EMPLOYEE).id,
                ),
            )
            if (employment == null) {
                // A server from before V30: it said nothing about the number or the kind,
                // so the phone's stay (see ServerWorker).
                dao.applySyncedWorker(worker.id, worker.name, worker.archivedAt, syncedAt)
            } else {
                dao.applySyncedWorkerDetails(
                    worker.id, worker.name, worker.phone, employment.id, worker.archivedAt, syncedAt,
                )
            }
        }
    }

    override suspend fun workersOf(sessionId: String): List<WorkerSlot> =
        dao.sessionWorkers(sessionId).map { it.toSlot() }

    override fun observeNamedOn(workerId: String): Flow<List<WorkerNaming>> =
        dao.observeNamingsOf(workerId).map { rows ->
            rows.map { WorkerNaming(it.sessionId, it.part?.let(WorkPart::fromId)) }
        }

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

    private fun WorkerEntity.toWorker() = Worker(
        id, name.orEmpty(), archived = archivedAt != null, phone = phone, employment = employmentOrDefault(),
    )

    /** A kind this build does not know, from a newer server, reads as an employee rather than failing. */
    private fun WorkerEntity.employmentOrDefault(): Employment =
        Employment.fromId(employment) ?: Employment.EMPLOYEE

    private fun SessionWorkerEntity.toSlot() =
        WorkerSlot(workerId = workerId, part = part?.let(WorkPart::fromId))

    private fun WorkerSlot.toEntity(sessionId: String) =
        SessionWorkerEntity(sessionId = sessionId, workerId = workerId, part = part?.id)
}
