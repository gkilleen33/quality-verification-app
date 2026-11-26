package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Test

/** The end of a Fundi Bora improvement list (v0.7.1, row 56, P17). */
class ImprovementEndTest {

    @Test
    fun `row 56 - the last improvement's Done opens the closing screen`() {
        // Every page but the last goes on to the next improvement.
        assertEquals(ImprovementPaging.Forward.Page(1), ImprovementPaging.forward(0, 3))
        assertEquals(ImprovementPaging.Forward.Page(2), ImprovementPaging.forward(1, 3))
        // The last page's button is "Done", and it opens the closing screen rather than
        // being a greyed "Next improvement".
        assertEquals(ImprovementPaging.Forward.Closing, ImprovementPaging.forward(2, 3))
        assertEquals(ImprovementPaging.Forward.Closing, ImprovementPaging.forward(0, 1))
        val words = FundiLabels.ENGLISH
        assertEquals("Next improvement", words.forwardLabel(ImprovementPaging.forward(0, 3)))
        assertEquals("Done", words.forwardLabel(ImprovementPaging.forward(2, 3)))
    }

    @Test
    fun `row 56 - the closing screen counts the improvements and names the piece`() {
        val words = FundiLabels.ENGLISH
        assertEquals("That's all 3 improvements", words.thatsAll(3))
        assertEquals("Fix them, then re-assess the daybed to see what changed.", words.fixThenReassess(3, "daybed"))
        assertEquals("Back to the daybed", words.backToPiece("daybed"))
        // A piece with no name of its own: its kind, or "the piece" for something else; in
        // Kiswahili always the word for a piece, since the kinds are English.
        val table = com.qualityverifier.domain.ItemType.WOODEN_TABLE
        assertEquals(table.homeLabel.lowercase(), words.closingPiece(null, table))
        assertEquals("piece", words.closingPiece(" ", com.qualityverifier.domain.ItemType.OTHER))
        assertEquals("Daybed for Wanjiru", words.closingPiece("Daybed for Wanjiru", com.qualityverifier.domain.ItemType.OTHER))
        assertEquals("kipande", FundiLabels.SWAHILI.closingPiece(null, com.qualityverifier.domain.ItemType.WOODEN_TABLE))
        // One improvement reads as one.
        assertEquals("That's the one improvement", words.thatsAll(1))
        assertEquals("Fix it, then re-assess the daybed to see what changed.", words.fixThenReassess(1, "daybed"))
    }
}
