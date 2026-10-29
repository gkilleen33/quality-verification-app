package com.qualityverifier.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** A message joined with its images, as Room hydrates it. */
data class MessageWithAttachments(
    @Embedded val message: MessageEntity,
    @Relation(parentColumn = "id", entityColumn = "messageId")
    val attachments: List<AttachmentEntity>,
)

/** One "who worked on this" row with the person's current name, for the opening turn. */
data class SessionWorkerSlotRow(val workerId: String?, val name: String?, val part: String?)

/** Just enough to decide whether a synced assessment is newer than the local copy. */
data class SessionStamp(val id: String, val updatedAt: Long)

/** Projection backing the home screen list. */
data class SessionSummaryRow(
    val id: String,
    val itemTypeId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val previewText: String,
    val messageCount: Int,
    val verdictLevelId: String?,
    val verdictLanguage: String?,
    val verdictUnverifiedCount: Int?,
    val pieceId: String?,
    /** From `pieces`, joined on the piece the session belongs to. */
    val pieceLabel: String?,
)

@Dao
interface SessionDao {

    @Query(
        """
        SELECT s.id AS id,
               s.itemTypeId AS itemTypeId,
               s.createdAt AS createdAt,
               s.updatedAt AS updatedAt,
               s.previewText AS previewText,
               s.verdictLevelId AS verdictLevelId,
               s.verdictLanguage AS verdictLanguage,
               s.verdictUnverifiedCount AS verdictUnverifiedCount,
               s.pieceId AS pieceId,
               p.label AS pieceLabel,
               COUNT(m.id) AS messageCount
        FROM sessions s
        LEFT JOIN messages m ON m.sessionId = s.id
        LEFT JOIN pieces p ON p.id = COALESCE(s.pieceId, s.id)
        GROUP BY s.id
        ORDER BY s.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<SessionSummaryRow>>

    @Insert
    suspend fun insertSession(session: SessionEntity)

    /**
     * Written once, and only into an empty slot.
     *
     * The `latitude IS NULL` guard makes a late second fix a no-op rather than a
     * correction: this records where the assessment *started*, and a fix that arrived
     * after the customer walked to the next stall would quietly move it.
     */
    @Query(
        "UPDATE sessions SET latitude = :latitude, longitude = :longitude, " +
            "locationAccuracyM = :accuracyM, locationAt = :capturedAt " +
            "WHERE id = :sessionId AND latitude IS NULL"
    )
    suspend fun setLocation(
        sessionId: String,
        latitude: Double,
        longitude: Double,
        accuracyM: Double,
        capturedAt: Long,
    )

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun findSession(sessionId: String): SessionEntity?

    @Query("UPDATE sessions SET updatedAt = :updatedAt, previewText = :preview WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long, preview: String)

    @Query(
        "UPDATE sessions SET verdictLevelId = :levelId, verdictLanguage = :language, " +
            "verdictUnverifiedCount = :unverifiedCount WHERE id = :sessionId"
    )
    suspend fun setVerdict(
        sessionId: String,
        levelId: String?,
        language: String?,
        unverifiedCount: Int?,
    )

    @Query("SELECT id FROM sessions")
    suspend fun allSessionIds(): List<String>

    @Query("DELETE FROM sessions WHERE id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    @Insert
    suspend fun insertMessage(message: MessageEntity)

    @Insert
    suspend fun insertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT COALESCE(MAX(ordinal), -1) + 1 FROM messages WHERE sessionId = :sessionId")
    suspend fun nextOrdinal(sessionId: String): Int

    @Transaction
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY ordinal ASC")
    fun observeMessages(sessionId: String): Flow<List<MessageWithAttachments>>

    @Transaction
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY ordinal ASC")
    suspend fun getMessages(sessionId: String): List<MessageWithAttachments>

    @Query("SELECT id, updatedAt FROM sessions")
    suspend fun sessionStamps(): List<SessionStamp>

    /** REPLACE, so an assessment synced twice produces one row rather than a conflict. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: SessionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachments(attachments: List<AttachmentEntity>)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesOf(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPendingDelete(pending: PendingRemoteDeleteEntity)

    @Query("SELECT sessionId FROM pending_remote_deletes ORDER BY requestedAt")
    suspend fun pendingDeletes(): List<String>

    @Query("DELETE FROM pending_remote_deletes WHERE sessionId = :sessionId")
    suspend fun clearPendingDelete(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTesterFeedback(feedback: PendingTesterFeedbackEntity)

    @Query("SELECT * FROM pending_tester_feedback ORDER BY recordedAt")
    suspend fun pendingTesterFeedback(): List<PendingTesterFeedbackEntity>

    @Query("SELECT COUNT(*) FROM pending_tester_feedback WHERE sessionId = :sessionId")
    suspend fun countTesterFeedback(sessionId: String): Int

    @Query("DELETE FROM pending_tester_feedback WHERE sessionId = :sessionId")
    suspend fun clearTesterFeedback(sessionId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPiece(piece: PieceEntity)

    /** A row for a piece the phone has never seen; a no-op when one exists. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPieceIfAbsent(piece: PieceEntity)

    /**
     * The server's name, applied only where the phone holds no unsent rename. One
     * statement rather than a read and a write, so a rename made in between cannot be
     * overwritten.
     */
    @Query(
        "UPDATE pieces SET label = :label, labelSyncedAt = :syncedAt " +
            "WHERE id = :pieceId AND labelSyncedAt IS NOT NULL"
    )
    suspend fun applySyncedLabel(pieceId: String, label: String?, syncedAt: Long)

    @Query("SELECT * FROM pieces WHERE id = :pieceId")
    suspend fun findPiece(pieceId: String): PieceEntity?

    /** Names the server has not been told yet, oldest first. */
    @Query("SELECT * FROM pieces WHERE labelSyncedAt IS NULL ORDER BY labelUpdatedAt")
    suspend fun unsyncedPieces(): List<PieceEntity>

    /**
     * Marks the name sent, unless it changed again while the send was in flight: the
     * guard on `labelUpdatedAt` leaves a newer rename unsent for the next sync.
     */
    @Query(
        "UPDATE pieces SET labelSyncedAt = :syncedAt WHERE id = :pieceId " +
            "AND (labelUpdatedAt = :asOf OR (:asOf IS NULL AND labelUpdatedAt IS NULL))"
    )
    suspend fun markPieceSynced(pieceId: String, asOf: Long?, syncedAt: Long)

    // ---- the roster

    @Query("SELECT * FROM workers ORDER BY updatedAt")
    fun observeWorkers(): Flow<List<WorkerEntity>>

    @Query("SELECT * FROM workers WHERE id = :workerId")
    suspend fun findWorker(workerId: String): WorkerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorker(worker: WorkerEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWorkerIfAbsent(worker: WorkerEntity)

    @Query("SELECT * FROM workers WHERE syncedAt IS NULL ORDER BY updatedAt")
    suspend fun unsyncedWorkers(): List<WorkerEntity>

    /** Guarded on `updatedAt`, so an edit made while the send was in flight stays unsent. */
    @Query("UPDATE workers SET syncedAt = :syncedAt WHERE id = :workerId AND updatedAt = :asOf")
    suspend fun markWorkerSynced(workerId: String, asOf: Long, syncedAt: Long)

    /**
     * The server's name and archive state, applied only where nothing is unsent. Archive
     * is sticky in both directions, so neither side can revive a worker the other let go.
     */
    @Query(
        "UPDATE workers SET name = :name, archivedAt = COALESCE(archivedAt, :archivedAt), " +
            "syncedAt = :syncedAt WHERE id = :workerId AND syncedAt IS NOT NULL"
    )
    suspend fun applySyncedWorker(workerId: String, name: String?, archivedAt: Long?, syncedAt: Long)

    // ---- who worked on a session

    @Query("SELECT * FROM session_workers WHERE sessionId = :sessionId ORDER BY id")
    suspend fun sessionWorkers(sessionId: String): List<SessionWorkerEntity>

    @Query(
        """
        SELECT sw.workerId AS workerId, w.name AS name, sw.part AS part
        FROM session_workers sw LEFT JOIN workers w ON w.id = sw.workerId
        WHERE sw.sessionId = :sessionId ORDER BY sw.id
        """
    )
    suspend fun sessionWorkerSlots(sessionId: String): List<SessionWorkerSlotRow>

    @Query("DELETE FROM session_workers WHERE sessionId = :sessionId")
    suspend fun deleteSessionWorkers(sessionId: String)

    @Insert
    suspend fun insertSessionWorkers(rows: List<SessionWorkerEntity>)

    /** The set for a session, replaced whole: merging two sets of the same answer is nobody's job. */
    @Transaction
    suspend fun replaceSessionWorkers(sessionId: String, rows: List<SessionWorkerEntity>) {
        deleteSessionWorkers(sessionId)
        if (rows.isNotEmpty()) insertSessionWorkers(rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addPendingSessionWorkers(pending: PendingSessionWorkersEntity)

    @Query("SELECT * FROM pending_session_workers ORDER BY requestedAt")
    suspend fun pendingSessionWorkers(): List<PendingSessionWorkersEntity>

    @Query("SELECT COUNT(*) FROM pending_session_workers WHERE sessionId = :sessionId")
    suspend fun countPendingSessionWorkers(sessionId: String): Int

    /** Guarded on `requestedAt`, so an edit made while the send was in flight stays pending. */
    @Query("DELETE FROM pending_session_workers WHERE sessionId = :sessionId AND requestedAt = :asOf")
    suspend fun clearPendingSessionWorkers(sessionId: String, asOf: Long)

    /** An edit from the card: the new set and the record that it is unsent, in one transaction. */
    @Transaction
    suspend fun editSessionWorkers(sessionId: String, rows: List<SessionWorkerEntity>, pending: PendingSessionWorkersEntity) {
        replaceSessionWorkers(sessionId, rows)
        addPendingSessionWorkers(pending)
    }

    /** The server's set, unless an edit is pending: checked and written in one transaction. */
    @Transaction
    suspend fun applySessionWorkersUnlessPending(sessionId: String, rows: List<SessionWorkerEntity>) {
        if (countPendingSessionWorkers(sessionId) == 0) replaceSessionWorkers(sessionId, rows)
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addDismissed(dismissed: DismissedSessionEntity)

    @Query("SELECT sessionId FROM dismissed_sessions")
    suspend fun dismissedSessions(): List<String>
}
