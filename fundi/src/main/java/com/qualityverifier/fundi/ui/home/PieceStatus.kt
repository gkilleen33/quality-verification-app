package com.qualityverifier.fundi.ui.home

import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.PieceStage

/**
 * A piece's Status row (v0.7.1, row 55): which stage shows, and where it came from.
 *
 * The newer of two (Nathan, 24 November 2026): the stage the fundi last set on the server,
 * and the stage the piece's latest assessment implies by its purpose. So a fundi who marks a
 * piece Finished and later assesses it as work in progress sees "In progress · from the last
 * assessment". "All my pieces" groups by the same value.
 */
object PieceStatus {
    data class Shown(val stage: PieceStage, val setByFundi: Boolean)

    /**
     * [seenAt] is when this phone stored the fundi's answer, and [latestStartedAt] when the
     * latest assessment was started, both on the phone's clock: the server's time of the
     * answer is never compared with a phone's, which is often minutes off. An answer stored
     * at or after that start wins.
     */
    fun shown(set: PieceStage?, seenAt: Long?, latestPurpose: FundiPurpose?, latestStartedAt: Long?): Shown {
        val implied = PieceStage.impliedBy(latestPurpose)
        if (set == null || seenAt == null) return Shown(implied, setByFundi = false)
        if (latestStartedAt == null || seenAt >= latestStartedAt) return Shown(set, setByFundi = true)
        return Shown(implied, setByFundi = false)
    }

    /**
     * The latest assessment is the one started last, not the one changed last: an older
     * assessment that gets a late reply does not decide what the piece's stage is.
     */
    fun of(piece: PieceSummary): Shown {
        val started = piece.assessments.maxBy { it.summary.createdAt }
        return shown(
            set = PieceStage.fromId(piece.latest.pieceStage),
            seenAt = piece.latest.pieceStageSeenAt,
            latestPurpose = started.purpose,
            latestStartedAt = started.summary.createdAt,
        )
    }
}

/** Who a report on a piece went to (V32's kinds), as "Reports sent" and its green box say it (row 56). */
enum class ReportRecipient(val kinds: Set<String>, val row: String, val inSentence: String) {
    CUSTOMER(setOf("customer"), "The customer", "the customer"),
    WORKER(setOf("part_issues", "part_fixes"), "A worker", "a worker"),
    ME(setOf("full"), "Me", "you"),
    MANAGER(setOf("to_manager"), "My manager", "your manager");

    companion object {
        fun of(kind: String): ReportRecipient? = entries.firstOrNull { kind in it.kinds }
    }
}
