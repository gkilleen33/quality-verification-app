package com.qualityverifier.fundi.home

import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.PieceStage
import com.qualityverifier.fundi.ui.home.PieceStatus
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
