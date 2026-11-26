package com.qualityverifier.fundi.home

import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.data.sync.SyncStep
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.fundi.ui.home.OfflineBox
import com.qualityverifier.fundi.ui.home.PieceAssessment
import com.qualityverifier.fundi.ui.home.PieceLines
import com.qualityverifier.domain.PieceStage
import com.qualityverifier.text.FundiScreenLabels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fundi Bora's v0.7.1 piece lists (rows 44, 45, 46, 56). */
class PieceLinesTest {

    /** 20 November 2026, 10:00 in Nairobi. */
    private val nov20 = 1_795_158_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun summary(id: String, piece: String, at: Long, label: String? = null) = SessionSummary(
        id = id, itemType = ItemType.WOODEN_TABLE, createdAt = at, updatedAt = at, preview = "", messageCount = 3,
        pieceId = piece, pieceLabel = label,
    )

    private suspend fun pieces(vararg summaries: SessionSummary, improvements: Int = 2) =
        PieceLines.piecesOf(summaries.toList()) { PieceAssessment(it, improvements, FundiPurpose.EVALUATE) }

    @Test
    fun `row 45 - a piece's line reads "4 improvements · checked 20 Nov"`() {
        assertEquals("4 improvements · checked 20 Nov", PieceLines.line(4, nov20))
        assertEquals("0 improvements · checked 20 Nov", PieceLines.line(0, nov20))
        // Unknown (a reply that would not read): the date alone.
        assertEquals("Checked 20 Nov", PieceLines.line(null, nov20))
    }

    @Test
    fun `row 45 - one finding reads "1 improvement"`() {
        assertEquals("1 improvement · checked 20 Nov", PieceLines.line(1, nov20))
    }

    @Test
    fun `row 45 - an assessment with no reply yet reads "Not finished"`() {
        val started = summary("a1", "a", nov20).copy(messageCount = 1)
        assertEquals("Not finished · started 20 Nov", PieceLines.line(PieceAssessment(started, 0, null)))
        assertEquals("2 improvements · checked 20 Nov", PieceLines.line(PieceAssessment(summary("a2", "a", nov20), 2, null)))
    }

    @Test
    fun `row 45 - pieces are one row each, newest first, named by their latest assessment`() = runTest {
        val list = pieces(
            summary("a1", "a", nov20 - 3 * day, label = "Daybed"),
            summary("b1", "b", nov20 - day),
            summary("a2", "a", nov20, label = "Daybed"),
        )
        assertEquals(listOf("a", "b"), list.map { it.pieceId })
        assertEquals("a2", list.first().latest.id)
        assertEquals(listOf("a2", "a1"), list.first().assessments.map { it.summary.id })
        assertEquals(nov20 - 3 * day, list.first().firstStartedAt)
    }

    @Test
    fun `row 56 - Home with no pieces offers "Assess your first piece"`() {
        val words = FundiScreenLabels.ENGLISH
        assertEquals("Assess your first piece", PieceLines.assessLabel(words, loaded = true, pieces = 0))
        assertEquals("Assess a new piece", PieceLines.assessLabel(words, loaded = true, pieces = 2))
        // Still loading: never "your first" to a maker whose pieces have not been read yet.
        assertEquals("Assess a new piece", PieceLines.assessLabel(words, loaded = false, pieces = 0))
    }

    @Test
    fun `row 56 - "All my pieces" appears only with more than three pieces`() {
        assertFalse(PieceLines.showsAll(3))
        assertTrue(PieceLines.showsAll(4))
        assertEquals("All my pieces (5)", FundiScreenLabels.ENGLISH.allPiecesCount(5))
    }

    @Test
    fun `row 56 - a piece is New for a day after its first assessment`() = runTest {
        val piece = pieces(summary("a1", "a", nov20)).single()
        assertTrue(PieceLines.isNew(piece, nov20 + day - 1))
        assertFalse(PieceLines.isNew(piece, nov20 + day))
    }

    @Test
    fun `row 56 - a failed sync shows the red box until a sync succeeds`() {
        val failed = SyncStatus(failedAt = nov20, failed = listOf(SyncStep.PULL))
        assertTrue(OfflineBox.shows(failed, syncing = false))
        // Try again hides it while the retry runs; a failed retry shows it again.
        assertFalse(OfflineBox.shows(failed, syncing = true))
        // A sync that reached the server clears it.
        assertFalse(OfflineBox.shows(SyncStatus(lastSucceededAt = nov20), syncing = false))
        // One refused row is not "can't reach the server".
        assertFalse(OfflineBox.shows(SyncStatus(failedAt = nov20, failed = listOf(SyncStep.PIECE_NAMES)), syncing = false))
        assertEquals(
            "You're seeing the pieces saved on this phone, from 20 Nov at 10:14. Check your internet connection.",
            FundiScreenLabels.ENGLISH.offlineLine("20 Nov", "10:14"),
        )
    }

    @Test
    fun `row 46 - New piece starts only when both answers are given, and says which is missing`() {
        assertEquals("Choose what you're working on and who it's for.", PieceLines.newPieceMissing(false, false))
        assertEquals("Choose what you're working on.", PieceLines.newPieceMissing(false, true))
        assertEquals("Choose who it's for.", PieceLines.newPieceMissing(true, false))
        assertNull(PieceLines.newPieceMissing(true, true))
    }

    @Test
    fun `the Your workshop lines`() {
        assertEquals("No workers yet", PieceLines.workersLine(0, 0))
        assertEquals("3 workers · 1 linked", PieceLines.workersLine(3, 1))
        assertEquals("1 worker · 0 linked", PieceLines.workersLine(1, 0))
        assertEquals("Only your own workshop", PieceLines.studiosLine(0))
        assertEquals("1 studio", PieceLines.studiosLine(1))
        assertEquals("Employee · 3 pieces", PieceLines.workerRowLine("Employee", 3))
        assertEquals("Temporary · 1 piece", PieceLines.workerRowLine("Temporary", 1))
    }

    @Test
    fun `the stage a purpose implies`() {
        assertEquals(PieceStage.IN_PROGRESS, PieceStage.impliedBy(FundiPurpose.WORK_IN_PROGRESS))
        assertEquals(PieceStage.FINISHED, PieceStage.impliedBy(FundiPurpose.EVALUATE))
        assertEquals(PieceStage.FINISHED, PieceStage.impliedBy(null))
    }
}
