package com.qualityverifier.server.db

/**
 * Who makes a piece's final assessment and sends its customer report (rows 27 and 51).
 *
 * One account does both, and two places decide it: `PostgresChatStore.pieceFooting`, which
 * keeps or drops an assessment's final flag, and `ReportService`, which allows the customer
 * report. Both call this, so the two cannot drift apart.
 *
 * The studio's linked manager when the piece is labelled for a studio that has one. The
 * piece's maker otherwise: on a piece for "My own job" or never labelled (row 27), and since
 * v0.7.0 on a piece labelled for a studio with no linked manager (row 51; Nathan, 23 November
 * 2026), such as a workshop whose owner does not use Fundi Bora. Before row 51 nobody could
 * make that piece's final assessment, so its customer report could never be sent. The piece
 * keeps its studio label; the assessment's `assessed_as` ("maker" or "manager") tells the
 * two cases apart in the data.
 */
object FinalAssessment {

    /** Whether the maker may make the final assessment of their own piece. */
    fun makerMayMakeFinal(unlabelled: Boolean, hasLinkedManager: Boolean): Boolean =
        unlabelled || !hasLinkedManager

    /** The account whose customer report it is: the linked manager, or else the maker. */
    fun customerReportAccount(makerId: String, studioEntryId: String?, linkedManagerId: String?): String =
        if (studioEntryId != null && linkedManagerId != null) linkedManagerId else makerId
}
