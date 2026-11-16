package com.qualityverifier.domain

/** A row in the reports list. */
data class SessionSummary(
    val id: String,
    val itemType: ItemType,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String,
    val messageCount: Int,
    /** Null while the assessment is still in progress. */
    val verdictLevel: VerdictLevel? = null,
    /** Language the verdict was written in; null when it was not recorded. */
    val verdictLanguage: String? = null,
    /**
     * How many things that verdict could not check. Null for a verdict recorded before
     * this was stored; the badge treats that as nothing unchecked.
     */
    val verdictUnverifiedCount: Int? = null,
    /** The physical piece, for Fundi Bora. Null means the session is its own piece. */
    val pieceId: String? = null,
    /** What the maker calls the piece. Null until they name it. */
    val pieceLabel: String? = null,
    /**
     * The studio the piece is for (Fundi Bora v0.6.0): an entry of "Studios I work for", or
     * null for "My own job" when [pieceStudioChosen], and for a piece never asked.
     */
    val pieceStudioId: String? = null,
    val pieceStudioChosen: Boolean = false,
    /** Whether the one correction of the label has been used. */
    val pieceStudioCorrected: Boolean = false,
    /** False for a linked worker's piece this account assessed as the studio's manager. */
    val pieceMine: Boolean = true,
    /** The final assessment of the finished piece, before a customer report. */
    val isFinal: Boolean = false,
) {
    /** Which piece this is, falling back to the session itself for rows made before pieces. */
    val piece: String get() = pieceId ?: id

    /** Drives the wording of the badge — see `ReportLabels.verdictWord`. */
    val anythingUnchecked: Boolean get() = (verdictUnverifiedCount ?: 0) > 0
}
