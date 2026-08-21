package com.qualityverifier.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
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
}
