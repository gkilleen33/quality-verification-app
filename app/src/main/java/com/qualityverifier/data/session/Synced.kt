package com.qualityverifier.data.session

import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role

/**
 * An assessment as the server has it, on its way into the local database.
 *
 * In the data layer rather than in `domain`, which is shared with the server: these exist
 * only to carry a fetched assessment into Room, and the server has no use for them.
 *
 * Separate from SessionSummary, which the reports list reads, because these carry the
 * fields sync needs to write and none of the ones the UI needs to draw.
 */
data class SyncedSession(
    val id: String,
    val itemType: ItemType,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String,
    val verdictLevelId: String?,
    val verdictLanguage: String?,
    val previousSessionId: String?,
    val intakeAnswers: String?,
)

data class SyncedMessage(
    val id: String,
    val role: Role,
    val text: String,
    val ordinal: Int,
    val createdAt: Long,
    /** Local file paths, already written by the sync before this reaches the database. */
    val attachmentPaths: List<String>,
)
