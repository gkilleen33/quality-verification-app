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
               COUNT(m.id) AS messageCount
        FROM sessions s
        LEFT JOIN messages m ON m.sessionId = s.id
        GROUP BY s.id
        ORDER BY s.updatedAt DESC
        """
    )
    fun observeSummaries(): Flow<List<SessionSummaryRow>>

    @Insert
    suspend fun insertSession(session: SessionEntity)

    @Query("SELECT * FROM sessions WHERE id = :sessionId")
    suspend fun findSession(sessionId: String): SessionEntity?

    @Query("UPDATE sessions SET updatedAt = :updatedAt, previewText = :preview WHERE id = :sessionId")
    suspend fun touchSession(sessionId: String, updatedAt: Long, preview: String)

    @Query(
        "UPDATE sessions SET verdictLevelId = :levelId, verdictLanguage = :language " +
            "WHERE id = :sessionId"
    )
    suspend fun setVerdict(sessionId: String, levelId: String?, language: String?)

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
}
