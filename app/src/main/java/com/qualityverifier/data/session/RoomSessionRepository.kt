package com.qualityverifier.data.session

import com.qualityverifier.data.db.AttachmentEntity
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.data.db.MessageEntity
import com.qualityverifier.data.db.MessageWithAttachments
import com.qualityverifier.data.db.SessionDao
import com.qualityverifier.data.db.SessionEntity
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.text.markdownToPlainText
import com.qualityverifier.text.parseAssistantContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomSessionRepository(
    private val dao: SessionDao,
    private val images: ImageFileStore,
    private val now: () -> Long = System::currentTimeMillis,
) : SessionRepository {

    override fun observeSummaries(): Flow<List<SessionSummary>> =
        dao.observeSummaries().map { rows ->
            rows.mapNotNull { row ->
                val itemType = ItemType.fromId(row.itemTypeId) ?: return@mapNotNull null
                SessionSummary(
                    id = row.id,
                    itemType = itemType,
                    createdAt = row.createdAt,
                    updatedAt = row.updatedAt,
                    preview = row.previewText,
                    messageCount = row.messageCount,
                    verdictLevel = row.verdictLevelId?.let(VerdictLevel::fromId),
                )
            }
        }

    override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
        dao.observeMessages(sessionId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun messagesOnce(sessionId: String): List<ChatMessage> =
        dao.getMessages(sessionId).map { it.toDomain() }

    override suspend fun itemTypeOf(sessionId: String): ItemType? =
        dao.findSession(sessionId)?.let { ItemType.fromId(it.itemTypeId) }

    override suspend fun sessionExists(sessionId: String): Boolean =
        dao.findSession(sessionId) != null

    override suspend fun createSession(sessionId: String, itemType: ItemType) {
        val timestamp = now()
        dao.insertSession(
            SessionEntity(
                id = sessionId,
                itemTypeId = itemType.id,
                createdAt = timestamp,
                updatedAt = timestamp,
                previewText = "",
            )
        )
    }

    override suspend fun appendUserMessage(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
    ): ChatMessage {
        val message = insert(sessionId, Role.USER, text, attachments)
        val preview = text.ifBlank {
            if (attachments.isEmpty()) "" else "${attachments.size} photo(s)"
        }
        dao.touchSession(sessionId, message.createdAt, preview.take(PREVIEW_LIMIT))
        return message
    }

    override suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage {
        val message = insert(sessionId, Role.ASSISTANT, text, emptyList())
        val content = parseAssistantContent(text)
        // A verdict turn is stored twice over: the level badges the reports row, and its
        // headline is a far better preview than the opening words of the prose.
        val preview = content.verdict?.headline?.ifBlank { null }
            // The list cannot show styling, so flatten the formatting rather than print it.
            // Flatten before truncating: cutting first can leave half a `**` pair behind.
            ?: markdownToPlainText(content.prose.ifBlank { text })
        dao.touchSession(sessionId, message.createdAt, preview.take(PREVIEW_LIMIT))
        content.verdict?.let { dao.setVerdictLevel(sessionId, it.level.id) }
        return message
    }

    override suspend fun deleteMessage(messageId: String) = dao.deleteMessage(messageId)

    override suspend fun deleteSession(sessionId: String) {
        dao.deleteSession(sessionId)
        images.deleteSessionImages(sessionId)
    }

    override suspend fun pruneOrphanImages() {
        images.pruneOrphans(dao.allSessionIds().toSet())
    }

    private suspend fun insert(
        sessionId: String,
        role: Role,
        text: String,
        attachments: List<Attachment>,
    ): ChatMessage {
        val id = UUID.randomUUID().toString()
        val timestamp = now()
        dao.insertMessage(
            MessageEntity(
                id = id,
                sessionId = sessionId,
                role = role.name,
                text = text,
                ordinal = dao.nextOrdinal(sessionId),
                createdAt = timestamp,
            )
        )
        if (attachments.isNotEmpty()) {
            dao.insertAttachments(
                attachments.map { attachment ->
                    AttachmentEntity(
                        id = attachment.id,
                        messageId = id,
                        relativePath = images.relativePathOf(java.io.File(attachment.path)),
                        mimeType = attachment.mimeType,
                    )
                }
            )
        }
        return ChatMessage(id, role, text, attachments, timestamp)
    }

    private fun MessageWithAttachments.toDomain() = ChatMessage(
        id = message.id,
        role = runCatching { Role.valueOf(message.role) }.getOrDefault(Role.USER),
        text = message.text,
        attachments = attachments.map {
            Attachment(
                id = it.id,
                path = images.resolve(it.relativePath).absolutePath,
                mimeType = it.mimeType,
            )
        },
        createdAt = message.createdAt,
    )

    private companion object {
        const val PREVIEW_LIMIT = 160
    }
}
