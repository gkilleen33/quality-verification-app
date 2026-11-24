package com.qualityverifier.fundi.home

import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.PieceStage
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.fundi.ui.home.PieceAssessment
import com.qualityverifier.fundi.ui.home.PieceStatus
import com.qualityverifier.fundi.ui.home.PieceSummary
import com.qualityverifier.fundi.ui.home.ReportRecipient
import com.qualityverifier.text.FundiScreenLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A piece's Status row (row 55) and who a sent report went to (row 56). */
class PieceStatusTest {

    @Test
    fun `row 55 - the newer of the set stage and the last assessment shows`() {
        // Never answered: the last assessment's purpose decides.
        assertEquals(PieceStatus.Shown(PieceStage.IN_PROGRESS, setByFundi = false), PieceStatus.shown(null, null, FundiPurpose.WORK_IN_PROGRESS, 100))
        assertEquals(PieceStatus.Shown(PieceStage.FINISHED, setByFundi = false), PieceStatus.shown(null, null, FundiPurpose.EVALUATE, 100))
        // Answered after the last assessment: the fundi's answer.
        assertEquals(PieceStatus.Shown(PieceStage.FINISHED, setByFundi = true), PieceStatus.shown(PieceStage.FINISHED, 200, FundiPurpose.WORK_IN_PROGRESS, 100))
        // Marked Finished, then assessed as work in progress: "In progress · from the last assessment".
        val later = PieceStatus.shown(PieceStage.FINISHED, 100, FundiPurpose.WORK_IN_PROGRESS, 200)
        assertEquals(PieceStatus.Shown(PieceStage.IN_PROGRESS, setByFundi = false), later)
        val words = FundiScreenLabels.ENGLISH
        assertEquals("In progress · from the last assessment", words.statusLine(words.inProgress, later.setByFundi))
        assertEquals("Finished · set by you", words.statusLine(words.finished, true))
    }

    @Test
    fun `row 55 - a phone clock ahead of the server's still shows the answer just saved`() {
        // The phone is 10 minutes fast. A work-in-progress assessment started at 10:00 is
        // stamped 10:10 on the phone; the fundi saves Finished at 10:05, which the server
        // stamps 10:05 and the phone stores at its own 10:15.
        val minute = 60_000L
        val started = 10 * 60 * minute + 10 * minute
        val wip = assessment("s1", createdAt = started, updatedAt = started, FundiPurpose.WORK_IN_PROGRESS)
        val piece = PieceSummary(
            pieceId = "p1",
            latest = wip.summary.copy(pieceStage = "finished", pieceStageSetAt = started - 5 * minute, pieceStageSeenAt = started + 5 * minute),
            assessments = listOf(wip),
            firstStartedAt = started,
        )
        assertEquals(PieceStatus.Shown(PieceStage.FINISHED, setByFundi = true), PieceStatus.of(piece))
    }

    @Test
    fun `row 55 - the assessment started last decides, not the one changed last`() {
        // An evaluation started first gets a late reply, so it is the most recently changed;
        // a work-in-progress check was started after it.
        val evaluate = assessment("s1", createdAt = 100, updatedAt = 900, FundiPurpose.EVALUATE)
        val wip = assessment("s2", createdAt = 500, updatedAt = 600, FundiPurpose.WORK_IN_PROGRESS)
        val piece = PieceSummary("p1", latest = evaluate.summary, assessments = listOf(evaluate, wip), firstStartedAt = 100)
        assertEquals(PieceStatus.Shown(PieceStage.IN_PROGRESS, setByFundi = false), PieceStatus.of(piece))
    }

    private fun assessment(id: String, createdAt: Long, updatedAt: Long, purpose: FundiPurpose) = PieceAssessment(
        summary = SessionSummary(
            id = id,
            itemType = ItemType.WOODEN_TABLE,
            createdAt = createdAt,
            updatedAt = updatedAt,
            preview = "",
            messageCount = 2,
            pieceId = "p1",
        ),
        improvements = null,
        purpose = purpose,
    )

    @Test
    fun `row 56 - each report kind names who it went to`() {
        assertEquals("The customer", ReportRecipient.of("customer")?.row)
        assertEquals("A worker", ReportRecipient.of("part_issues")?.row)
        assertEquals("A worker", ReportRecipient.of("part_fixes")?.row)
        assertEquals("Me", ReportRecipient.of("full")?.row)
        assertEquals("My manager", ReportRecipient.of("to_manager")?.row)
        // A worker-work report is on a worker, not a piece, and is never listed on one.
        assertNull(ReportRecipient.of("worker_work"))
        assertEquals("Report sent to the customer", FundiScreenLabels.ENGLISH.reportSentTo(ReportRecipient.CUSTOMER.inSentence))
    }
}
