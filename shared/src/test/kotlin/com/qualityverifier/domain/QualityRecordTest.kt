package com.qualityverifier.domain

import com.qualityverifier.domain.QualityRecord.Piece
import com.qualityverifier.domain.QualityRecord.Rate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rates, and the edges that would quietly misreport a shop.
 *
 * Worth testing hard because the numbers are going to be shown to the maker they describe
 * and, later, to buyers deciding whether to trust them. A rate that is wrong by one is not
 * a rounding error here; it is a claim about somebody's work.
 */
class QualityRecordTest {

    private var next = 0
    private fun piece(vararg defectCounts: Int?) = Piece("p${next++}", defectCounts.toList())

    // --------------------------------------------------- first time clean

    @Test
    fun `clean first time counts the first verdict only`() {
        val pieces = listOf(
            piece(0),        // clean
            piece(2, 0),     // flagged, later fixed — still not clean first time
            piece(0),        // clean
            piece(1),        // flagged, never fixed
        )

        assertEquals(Rate(2, 4), QualityRecord.firstTimeClean(pieces))
    }

    @Test
    fun `the denominator shrinks below ten rather than pretending`() {
        // "3 of 10" for a maker with four pieces would read as seven failures that never
        // happened. The rate has to carry the denominator it actually used.
        val pieces = listOf(piece(0), piece(0), piece(1), piece(0))

        val rate = QualityRecord.firstTimeClean(pieces)
        assertEquals(3, rate.count)
        assertEquals(4, rate.outOf)
    }

    @Test
    fun `only the last ten pieces count`() {
        // Twelve pieces: the two oldest were clean and must fall out of the window, so a
        // maker's record moves when their work does.
        val pieces = listOf(piece(0), piece(0)) + (1..10).map { piece(3) }

        assertEquals(Rate(0, 10), QualityRecord.firstTimeClean(pieces))
    }

    @Test
    fun `an assessment with no verdict is not a clean piece`() {
        // The trap this whole design turns on. Every full assessment between 3 and 8
        // September produced no verdict, because the reply was truncated at max_tokens
        // and the unparseable block was dropped. Counting those as defect-free would
        // have reported a shop that never once got a verdict as flawless.
        val pieces = listOf(piece(null), piece(null), piece(0))

        val rate = QualityRecord.firstTimeClean(pieces)
        assertEquals("only the piece with a verdict counts", 1, rate.count)
        assertEquals("and only it is in the denominator", 1, rate.outOf)
    }

    @Test
    fun `a failed assessment is skipped, not held against the piece`() {
        // The first attempt truncated; the retry produced a clean verdict. That piece was
        // right first time — the failure was ours, and it should cost a data point at
        // most, never the maker's record.
        val pieces = listOf(piece(null, 0))

        assertEquals(Rate(1, 1), QualityRecord.firstTimeClean(pieces))
    }

    @Test
    fun `a maker with nothing assessed has no rate rather than a zero`() {
        assertFalse(QualityRecord.firstTimeClean(emptyList()).hasData)
        assertFalse(QualityRecord.firstTimeClean(listOf(piece(null))).hasData)
        // Zero of zero is not a score, and showing it as one would say something false
        // about somebody who has simply not started.
        assertEquals(0, QualityRecord.firstTimeClean(emptyList()).outOf)
    }

    // ------------------------------------------------------- repairs

    @Test
    fun `a repair is confirmed by a later clean verdict`() {
        val pieces = listOf(
            piece(2, 0),     // flagged, then clean — repaired
            piece(1),        // flagged, never re-assessed
            piece(3, 1),     // flagged, re-assessed, still flagged
        )

        assertEquals(Rate(1, 3), QualityRecord.repairsConfirmed(pieces))
    }

    @Test
    fun `a second attempt at the repair still counts`() {
        // Re-glued, re-assessed, still gapping, fixed properly, re-assessed clean. That
        // piece was repaired. Counting only the first retry would punish the more
        // careful path.
        val pieces = listOf(piece(2, 1, 0))

        assertEquals(Rate(1, 1), QualityRecord.repairsConfirmed(pieces))
    }

    @Test
    fun `clean pieces are not in the repair denominator`() {
        // Nothing was flagged, so there was nothing to repair. Including them would make
        // a flawless maker look like a poor repairer.
        val pieces = listOf(piece(0), piece(0), piece(0))

        assertFalse(QualityRecord.repairsConfirmed(pieces).hasData)
    }

    @Test
    fun `the two windows are independent`() {
        // Ten clean pieces since, and one flagged piece long ago that was repaired. The
        // clean rate looks only at the recent ten; the repair rate still reports on the
        // mistake, because "when you do make one, how does it go" is a question about the
        // mistakes whenever they happened.
        val pieces = listOf(piece(2, 0)) + (1..10).map { piece(0) }

        assertEquals(Rate(10, 10), QualityRecord.firstTimeClean(pieces))
        assertEquals(Rate(1, 1), QualityRecord.repairsConfirmed(pieces))
    }

    @Test
    fun `a piece whose first verdict never arrived is not treated as flagged`() {
        // firstVerdict is null, so it is neither clean nor flagged. It must not drift
        // into the repair denominator through a null-coalescing accident.
        val pieces = listOf(piece(null, 3))

        // Its first *verdict* is 3, so it is flagged and unrepaired.
        assertEquals(Rate(0, 1), QualityRecord.repairsConfirmed(pieces))

        val neverEvaluated = listOf(piece(null, null))
        assertFalse(QualityRecord.repairsConfirmed(neverEvaluated).hasData)
    }

    @Test
    fun `only the last ten flagged pieces count`() {
        val old = (1..5).map { piece(1, 0) }          // repaired, but too old
        val recent = (1..10).map { piece(1) }          // flagged, unrepaired
        val pieces = old + recent

        assertEquals(Rate(0, 10), QualityRecord.repairsConfirmed(pieces))
    }

    @Test
    fun `repairConfirmed ignores the first verdict even when it is clean`() {
        // Guards the drop(1): a single clean verdict must not read as its own repair.
        assertFalse(piece(0).repairConfirmed)
        assertTrue(piece(2, 0).repairConfirmed)
    }
}
