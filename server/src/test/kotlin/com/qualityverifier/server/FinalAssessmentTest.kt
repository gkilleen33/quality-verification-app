package com.qualityverifier.server

import com.qualityverifier.server.db.FinalAssessment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who makes a piece's final assessment and sends its customer report (rows 27 and 51). The
 * chat store's SQL reads the two facts this takes (labelled, linked manager) from V34's
 * piece_managers; the rule itself is here, shared with the report service.
 */
class FinalAssessmentTest {

    private val maker = "maker"
    private val manager = "manager"
    private val entry = "studio-entry"

    @Test
    fun `row 51 - a maker with an unlinked studio may make the final assessment`() {
        assertTrue(FinalAssessment.makerMayMakeFinal(unlabelled = false, hasLinkedManager = false))
        assertEquals(maker, FinalAssessment.customerReportAccount(maker, entry, linkedManagerId = null))
    }

    @Test
    fun `row 51 - a maker whose studio manager is linked still may not`() {
        assertFalse(FinalAssessment.makerMayMakeFinal(unlabelled = false, hasLinkedManager = true))
        assertEquals(manager, FinalAssessment.customerReportAccount(maker, entry, linkedManagerId = manager))
    }

    @Test
    fun `row 27 - the maker of a piece for My own job makes it, as before`() {
        assertTrue(FinalAssessment.makerMayMakeFinal(unlabelled = true, hasLinkedManager = false))
        assertEquals(maker, FinalAssessment.customerReportAccount(maker, null, null))
    }
}
