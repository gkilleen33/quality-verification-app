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
     * [setAt] is the server's time of the fundi's answer; [latestStartedAt] when the latest
     * assessment was started, on the phone's clock. An answer at or after that start wins.
     */
    fun shown(set: PieceStage?, setAt: Long?, latestPurpose: FundiPurpose?, latestStartedAt: Long?): Shown {
        val implied = PieceStage.impliedBy(latestPurpose)
        if (set == null || setAt == null) return Shown(implied, setByFundi = false)
        if (latestStartedAt == null || setAt >= latestStartedAt) return Shown(set, setByFundi = true)
        return Shown(implied, setByFundi = false)
    }

    fun of(piece: PieceSummary): Shown = shown(
        set = PieceStage.fromId(piece.latest.pieceStage),
        setAt = piece.latest.pieceStageSetAt,
        latestPurpose = piece.newest.purpose,
        latestStartedAt = piece.newest.summary.createdAt,
    )
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
