package com.qualityverifier.domain

/**
 * Whether a piece is being made or finished (v0.7.1, row 55): the fundi's answer to "Is this
 * piece finished?" on the piece's page, stored on the server (V44's `pieces.stage`, whose
 * CHECK mirrors these ids), and what "All my pieces" groups by.
 */
enum class PieceStage(val id: String) {
    IN_PROGRESS("in_progress"),
    FINISHED("finished");

    companion object {
        val ids: List<String> = entries.map { it.id }

        fun fromId(id: String?): PieceStage? = entries.firstOrNull { it.id == id }

        /**
         * The stage an assessment's purpose implies: checking work in progress means the piece
         * is being made; any other purpose means it is finished (Nathan, 24 November 2026).
         * Unknown reads as finished.
         */
        fun impliedBy(purpose: FundiPurpose?): PieceStage =
            if (purpose == FundiPurpose.WORK_IN_PROGRESS) IN_PROGRESS else FINISHED
    }
}
