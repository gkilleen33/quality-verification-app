package com.qualityverifier.fundi.workers

import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Problem
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.WorkerPiece
import com.qualityverifier.domain.WorkerPieceAssessment
import com.qualityverifier.domain.WorkerPieceProblem
import com.qualityverifier.fundi.ui.workers.WorkerPageRows
import com.qualityverifier.fundi.ui.workers.WorkerPages
import org.junit.Assert.assertEquals
import org.junit.Test

/** A worker's page (v0.7.1, row 44): one row per piece, its name and its line, nothing more. */
class WorkerPageRowsTest {

    /** 20 November 2026, 10:00 in Nairobi. */
    private val nov20 = 1_795_158_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun summary(id: String, piece: String, at: Long, label: String? = "Daybed") = SessionSummary(
        id = id, itemType = ItemType.WOODEN_BED, createdAt = at, updatedAt = at, preview = "", messageCount = 3,
        pieceId = piece, pieceLabel = label,
    )

    private fun problem(title: String) = Problem(title, "", "minor", "", "", "", "")

    @Test
    fun `row 44 - a worker's page lists each piece once, with its line and nothing more`() {
        val newer = summary("a2", "a", nov20)
        val older = summary("a1", "a", nov20 - 3 * day)
        val piece = WorkerPages.Piece(
            pieceId = "a",
            latest = newer,
            parts = listOf(null),
            assessments = listOf(
                WorkerPages.Assessment(newer, null, List(4) { problem("p$it") }),
                WorkerPages.Assessment(older, null, List(2) { problem("q$it") }),
            ),
        )
        // The same piece twice (two namings) is still one row.
        val rows = WorkerPageRows.named(listOf(piece, piece))
        assertEquals(listOf(WorkerPageRows.Row("a", "Daybed", "4 improvements · checked 20 Nov")), rows)

        val theirs = WorkerPageRows.theirs(
            listOf(
                WorkerPiece(
                    id = "t", itemTypeId = ItemType.WOODEN_TABLE.id, label = null, theirs = true,
                    assessments = listOf(
                        WorkerPieceAssessment("s1", nov20 - day, byWorker = true, problems = listOf(WorkerPieceProblem("x", "minor"))),
                        WorkerPieceAssessment("s2", nov20, byWorker = true, problems = emptyList()),
                    ),
                ),
            ),
        )
        assertEquals(listOf(WorkerPageRows.Row("t", ItemType.WOODEN_TABLE.homeLabel, "0 improvements · checked 20 Nov")), theirs)
    }
}
