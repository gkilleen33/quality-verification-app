package com.qualityverifier.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `serverId` and `updatedAt` are unused in Phase 1 but present from the first schema
 * version so that Phase 2 sync needs no migration.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val itemTypeId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val previewText: String,
    val serverId: String? = null,
    /**
     * The last verdict level this assessment reached, so the reports list can show a
     * badge without loading and re-parsing every conversation. Null until a verdict
     * arrives, which is the normal state for an assessment still in progress.
     */
    val verdictLevelId: String? = null,
    /**
     * Language of that verdict, so the reports list can badge it in the same language
     * the assessment was written in. Null for a verdict stored before this was recorded.
     */
    val verdictLanguage: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val ordinal: Int,
    val createdAt: Long,
    val serverId: String? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    /** Path relative to the app's files directory, e.g. `images/<sessionId>/<uuid>.jpg`. */
    val relativePath: String,
    val mimeType: String,
)
