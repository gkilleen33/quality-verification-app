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
    val pieceId: String? = null,
    val pieceLabel: String? = null,
    /** False for a linked worker's piece this account assessed as the studio's manager (V34). */
    val pieceMine: Boolean = true,
    /** The piece's studio label (V34); empty for a piece that is not this account's. */
    val pieceStudioId: String? = null,
    val pieceStudioChosen: Boolean = false,
    val pieceStudioCorrected: Boolean = false,
    val isFinal: Boolean = false,
)

data class SyncedMessage(
    val id: String,
    val role: Role,
    val text: String,
    val ordinal: Int,
    val createdAt: Long,
    /** Local file paths, already written by the sync before this reaches the database. */
    val attachmentPaths: List<String>,
    /** Written by the app on the user's behalf. Carried so a rebuilt history keeps it. */
    val composed: Boolean = false,
)
