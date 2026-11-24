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
/** One synced photo and the server's number for it in its conversation (v0.6.0, row 19). */
data class PhotoNumberRow(val relativePath: String, val photoNumber: Int)

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
    /** From `pieces` too (V34): the label, whether it was chosen and corrected, and whose piece. */
    val pieceStudioId: String? = null,
    val pieceStudioChosen: Boolean = false,
    val pieceStudioCorrected: Boolean = false,
    val pieceMine: Boolean = true,
    val isFinal: Boolean = false,
    /** From `pieces` (server V44, row 55): the maker's last answer, and the server's time of it. */
    val pieceStage: String? = null,
    val pieceStageSetAt: Long? = null,
    /** When this phone stored [pieceStage], on its own clock. */
    val pieceStageSeenAt: Long? = null,
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
               p.studioId AS pieceStudioId,
               COALESCE(p.studioChosen, 0) AS pieceStudioChosen,
               COALESCE(p.studioCorrected, 0) AS pieceStudioCorrected,
               COALESCE(p.mine, 1) AS pieceMine,
               s.isFinal AS isFinal,
               p.stage AS pieceStage,
               p.stageSetAt AS pieceStageSetAt,
               p.stageSeenAt AS pieceStageSeenAt,
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

    /**
     * A rename, as an update rather than a replace: replacing the row would reset the
     * piece's studio label (V34) to "never asked".
     */
    @Query(
        "UPDATE pieces SET label = :label, labelUpdatedAt = :updatedAt, labelSyncedAt = NULL " +
            "WHERE id = :pieceId"
    )
    suspend fun renamePiece(pieceId: String, label: String?, updatedAt: Long)

    // ---- a piece's studio label (server V34)

    /**
     * The server's label, applied only where the phone holds no unsent change of its own,
     * in one statement for the reason [applySyncedLabel] gives. [mine] is applied always:
     * whose piece it is never changes on the phone.
     */
    @Query(
        "UPDATE pieces SET mine = :mine, " +
            "studioId = CASE WHEN studioPendingAt IS NULL THEN :studioId ELSE studioId END, " +
            "studioChosen = CASE WHEN studioPendingAt IS NULL THEN :chosen ELSE studioChosen END, " +
            "studioCorrected = CASE WHEN studioPendingAt IS NULL THEN :corrected ELSE studioCorrected END " +
            "WHERE id = :pieceId"
    )
    suspend fun applySyncedStudio(pieceId: String, studioId: String?, chosen: Boolean, corrected: Boolean, mine: Boolean)

    /**
     * The maker's own change of label, waiting to be sent. A first answer for a piece never
     * asked does not spend the correction; any later change does.
     */
    @Query(
        "UPDATE pieces SET studioId = :studioId, " +
            "studioCorrected = CASE WHEN studioChosen THEN 1 ELSE studioCorrected END, " +
            "studioChosen = 1, studioPendingAt = :at WHERE id = :pieceId AND mine"
    )
    suspend fun setPieceStudio(pieceId: String, studioId: String?, at: Long)

    /**
     * A piece's stage as the server stored it (V44, row 55), from the answer to a set or from a
     * sync, kept only when newer than what the phone holds, so an older answer arriving late
     * never replaces a newer one. [seenAt] is the phone's clock as it stores it.
     *
     * The row is made first when the phone has none (a piece from before Room 11 has none), so
     * an answer the server accepted always shows.
     */
    @Transaction
    suspend fun applyPieceStage(pieceId: String, stage: String, setAt: Long, seenAt: Long) {
        insertPieceIfAbsent(PieceEntity(id = pieceId))
        updatePieceStage(pieceId, stage, setAt, seenAt)
    }

    @Query(
        "UPDATE pieces SET stage = :stage, stageSetAt = :setAt, stageSeenAt = :seenAt " +
            "WHERE id = :pieceId AND (stageSetAt IS NULL OR stageSetAt < :setAt)"
    )
    suspend fun updatePieceStage(pieceId: String, stage: String, setAt: Long, seenAt: Long)

    @Query("SELECT * FROM pieces WHERE studioPendingAt IS NOT NULL ORDER BY studioPendingAt")
    suspend fun unsyncedPieceStudios(): List<PieceEntity>

    /**
     * Pieces with a name, a studio change, or an assessment's "who worked on this" the server
     * has not had, for "Not sent yet". A worker set waits while its assessment's opening turn
     * is unsent, and the sync reports it as not finished, so its piece carries the tag too.
     * A first assessment's piece is its own session id.
     */
    @Query(
        "SELECT id FROM pieces WHERE labelSyncedAt IS NULL OR studioPendingAt IS NOT NULL " +
            "UNION SELECT COALESCE(s.pieceId, s.id) FROM sessions s " +
            "JOIN pending_session_workers p ON p.sessionId = s.id"
    )
    fun observeUnsentPieceIds(): Flow<List<String>>

    /** Marks the change sent, unless another was made while it was in flight. */
    @Query("UPDATE pieces SET studioPendingAt = NULL WHERE id = :pieceId AND studioPendingAt = :asOf")
    suspend fun markPieceStudioSent(pieceId: String, asOf: Long)

    // ---- which studio today (server V34)

    @Query("SELECT * FROM studio_days WHERE day = :day")
    suspend fun studioDay(day: String): StudioDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStudioDay(day: StudioDayEntity)

    @Query("SELECT * FROM studio_days WHERE syncedAt IS NULL ORDER BY answeredAt")
    suspend fun unsyncedStudioDays(): List<StudioDayEntity>

    @Query("UPDATE studio_days SET syncedAt = :syncedAt WHERE day = :day")
    suspend fun markStudioDaySynced(day: String, syncedAt: Long)

    /** Each synced photo of a session with the server's number for it (v0.6.0, row 19). */
    @Query(
        "SELECT a.relativePath AS relativePath, a.photoNumber AS photoNumber FROM attachments a " +
            "JOIN messages m ON m.id = a.messageId WHERE m.sessionId = :sessionId AND a.photoNumber IS NOT NULL"
    )
    suspend fun photoNumbers(sessionId: String): List<PhotoNumberRow>

    @Query("SELECT helpful FROM review_helpful WHERE sessionId = :sessionId")
    fun reviewHelpful(sessionId: String): Flow<Boolean?>

    /** IGNORE: the first answer stands, as on the server. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReviewHelpful(answer: ReviewHelpfulEntity)

    @Query("SELECT * FROM review_helpful WHERE syncedAt IS NULL ORDER BY answeredAt")
    suspend fun unsyncedReviewHelpful(): List<ReviewHelpfulEntity>

    @Query("UPDATE review_helpful SET syncedAt = :syncedAt WHERE sessionId = :sessionId")
    suspend fun markReviewHelpfulSynced(sessionId: String, syncedAt: Long)

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

    /** The ids of [unsyncedWorkers], as they change, for the "Not sent yet" tag. */
    @Query("SELECT id FROM workers WHERE syncedAt IS NULL")
    fun observeUnsyncedWorkerIds(): Flow<List<String>>

    /** Guarded on `updatedAt`, so an edit made while the send was in flight stays unsent. */
    @Query("UPDATE workers SET syncedAt = :syncedAt WHERE id = :workerId AND updatedAt = :asOf")
    suspend fun markWorkerSynced(workerId: String, asOf: Long, syncedAt: Long)

    /**
     * The server's name and archive state, applied only where nothing is unsent. Archive
     * is sticky in both directions, so neither side can revive a worker the other let go.
     *
     * For a server from before V30, which sends no number or kind: those stay as the
     * phone holds them rather than being read as "none".
     */
    @Query(
        "UPDATE workers SET name = :name, archivedAt = COALESCE(archivedAt, :archivedAt), " +
            "syncedAt = :syncedAt WHERE id = :workerId AND syncedAt IS NOT NULL"
    )
    suspend fun applySyncedWorker(workerId: String, name: String?, archivedAt: Long?, syncedAt: Long)

    /**
     * As [applySyncedWorker], plus the number and the kind, which follow the name's
     * rule: the server's copy wins where nothing is unsent, and a null number is a
     * number removed on another phone.
     */
    @Query(
        "UPDATE workers SET name = :name, phone = :phone, employment = :employment, linked = :linked, " +
            "archivedAt = COALESCE(archivedAt, :archivedAt), " +
            "syncedAt = :syncedAt WHERE id = :workerId AND syncedAt IS NOT NULL"
    )
    suspend fun applySyncedWorkerDetails(
        workerId: String,
        name: String?,
        phone: String?,
        employment: String,
        linked: Boolean,
        archivedAt: Long?,
        syncedAt: Long,
    )

    // ---- the studios this account works at (V31)

    @Query("SELECT * FROM worker_studios ORDER BY updatedAt")
    fun observeStudios(): Flow<List<StudioEntity>>

    @Query("SELECT * FROM worker_studios WHERE id = :studioId")
    suspend fun findStudio(studioId: String): StudioEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStudio(studio: StudioEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertStudioIfAbsent(studio: StudioEntity)

    @Query("SELECT * FROM worker_studios WHERE syncedAt IS NULL ORDER BY updatedAt")
    suspend fun unsyncedStudios(): List<StudioEntity>

    /** Guarded on `updatedAt`, so an edit made while the send was in flight stays unsent. */
    @Query("UPDATE worker_studios SET syncedAt = :syncedAt WHERE id = :studioId AND updatedAt = :asOf")
    suspend fun markStudioSynced(studioId: String, asOf: Long, syncedAt: Long)

    /**
     * The server's copy, applied only where nothing is unsent. Archive is sticky in both
     * directions, as for a worker; the match is the server's to say.
     */
    @Query(
        "UPDATE worker_studios SET name = :name, phone = :phone, linked = :linked, " +
            "archivedAt = COALESCE(archivedAt, :archivedAt), " +
            "syncedAt = :syncedAt WHERE id = :studioId AND syncedAt IS NOT NULL"
    )
    suspend fun applySyncedStudio(
        studioId: String,
        name: String?,
        phone: String?,
        linked: Boolean,
        archivedAt: Long?,
        syncedAt: Long,
    )

    // ---- who worked on a session

    @Query("SELECT * FROM session_workers WHERE sessionId = :sessionId ORDER BY id")
    suspend fun sessionWorkers(sessionId: String): List<SessionWorkerEntity>

    /**
     * Every assessment one worker is named on, with the part, for the worker's page. A
     * flow, so the page follows an edit to "Who worked on this?" made while it is open.
     */
    @Query("SELECT * FROM session_workers WHERE workerId = :workerId ORDER BY id")
    fun observeNamingsOf(workerId: String): Flow<List<SessionWorkerEntity>>

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
