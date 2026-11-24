package com.qualityverifier.text

import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.Finding
import com.qualityverifier.domain.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reply from before prompt E in the improvement-list design (v0.7.1, row 54). */
class OlderRepliesTest {

    // The one-list shape (after v0.4.0, before prompt E): consequences on the findings.
    private val older = Diagnosis(
        findings = listOf(
            Finding(
                title = "Glue the loose rail",
                severityId = "moderate",
                whatHappened = "The lower rail moves when pushed.",
                manufacturingCause = "The joint was dry-fitted and never glued.",
                rootHabit = "Gluing is left until the end.",
                whatItMeans = "A customer leaning on it feels it give.",
                whatToDo = "Glue and clamp the rail overnight.",
            ),
            Finding(title = "Sand the edges", severityId = "cosmetic"),
        ),
    )

    @Test
    fun `row 54 - a reply from before prompt E draws as a list, with what it means under "What the customer thinks"`() {
        assertFalse("the reply is older", older.isImprovementList)
        val list = olderReplyAsList(null, older)
        assertTrue(list.isImprovementList)
        val first = list.findings.first()
        assertEquals("A customer leaning on it feels it give.", first.customerLine)
        assertEquals("Glue and clamp the rail overnight.", first.fixLine)
        // The details stay as stored, behind the page's buttons; no short row line is made up.
        assertEquals("The lower rail moves when pushed.", first.whatHappened)
        assertEquals("The joint was dry-fitted and never glued.", first.manufacturingCause)
        assertEquals("Gluing is left until the end.", first.rootHabit)
        assertEquals("", first.rowLine)
        // A finding with nothing stored keeps its title and its place in the numbering.
        assertEquals("", list.findings[1].customerLine)
        assertEquals(listOf(1, 2), improvementRows(list).map { it.number })
    }

    @Test
    fun `row 54 - the oldest shape takes what it means and what to do from the verdict's defects, by title`() {
        val oldest = Diagnosis(
            findings = listOf(
                Finding(title = "Sand the edges", severityId = "cosmetic"),
                Finding(title = "Glue the loose rail", severityId = "moderate", whatHappened = "It moves."),
            ),
        )
        val verdict = Verdict(
            defects = listOf(
                Defect(title = "glue the loose rail ", whatItMeans = "It wobbles.", whatToDo = "Glue it."),
                Defect(title = "Sand the edges", whatItMeans = "Rough to touch.", whatToDo = "Sand to 180."),
            ),
        )
        val list = olderReplyAsList(verdict, oldest)
        // The rows stay the findings, in their order, so each keeps its number.
        assertEquals(listOf("Sand the edges", "Glue the loose rail"), list.findings.map { it.title })
        assertEquals("Sand to 180.", list.findings[0].fixLine)
        assertEquals("It wobbles.", list.findings[1].customerLine)
        assertEquals("It moves.", list.findings[1].whatHappened)
    }

    @Test
    fun `row 54 - a defect with no finding of its title is not given to another finding`() {
        val oldest = Diagnosis(findings = listOf(Finding(title = "Sand the edges"), Finding(title = "Fill the gap")))
        val verdict = Verdict(defects = listOf(Defect(title = "Glue the loose rail", whatToDo = "Glue it.")))
        // Nothing to fix on any finding: the reply keeps the card it was drawn with.
        assertSame(oldest, olderReplyAsList(verdict, oldest))
    }

    @Test
    fun `row 54 - a defect one finding took by title is not given to another by place`() {
        // Same length, so place could decide; but the defect at "Rough edges"'s place is the
        // gap, which "Gap at the back joint" already took by title.
        val oldest = Diagnosis(findings = listOf(Finding(title = "Gap at the back joint"), Finding(title = "Rough edges")))
        val verdict = Verdict(
            defects = listOf(
                Defect(title = "Rough edges on top", whatToDo = "Sand to 180."),
                Defect(title = "Gap at the back joint", whatToDo = "Re-glue the joint."),
            ),
        )
        val list = olderReplyAsList(verdict, oldest)
        assertEquals("Re-glue the joint.", list.findings[0].fixLine)
        assertEquals("", list.findings[1].fixLine)
    }

    @Test
    fun `row 54 - two findings of one title do not share the second defect's fix`() {
        val oldest = Diagnosis(findings = listOf(Finding(title = "Loose joint"), Finding(title = "Loose joint")))
        val verdict = Verdict(
            defects = listOf(
                Defect(title = "Loose joint", whatToDo = "Glue the front joint."),
                Defect(title = "Loose joint", whatToDo = "Glue the back joint."),
            ),
        )
        val list = olderReplyAsList(verdict, oldest)
        assertEquals(listOf("Glue the front joint.", "Glue the back joint."), list.findings.map { it.fixLine })
    }

    @Test
    fun `row 54 - what I see answers "More on what we found" where nothing else is stored`() {
        val oneList = Diagnosis(
            findings = listOf(Finding(title = "Gap", whatISee = "A 3 mm gap at the back.", whatToDo = "Re-glue it.")),
        )
        assertEquals("A 3 mm gap at the back.", olderReplyAsList(null, oneList).findings.single().whatHappened)
    }

    @Test
    fun `row 54 - drawing an older reply makes no Claude call`() {
        // The mapping is a pure function in :shared, which has no network client, and every
        // line it draws is a line already in the stored reply: nothing is written for it.
        // (The screen asks Claude only when the maker taps a detail with nothing stored.)
        val list = olderReplyAsList(null, older)
        val stored = older.findings.flatMap { listOf(it.title, it.whatHappened, it.manufacturingCause, it.rootHabit, it.whatISee, it.whatItMeans, it.whatToDo) }
            .filter { it.isNotBlank() }
            .toSet()
        val drawn = list.findings.flatMap { listOf(it.title, it.customerLine, it.fixLine, it.rowLine, it.toolLine, it.whatHappened, it.manufacturingCause, it.rootHabit) }
            .filter { it.isNotBlank() }
        assertTrue(drawn.isNotEmpty())
        assertTrue("every drawn line is stored: $drawn", drawn.all { it in stored })
    }

    @Test
    fun `row 54 - a reply already in prompt E's shape is drawn as it is`() {
        val newer = older.copy(findings = older.findings.map { it.copy(fixLine = "Fix it.") })
        assertSame(newer, olderReplyAsList(null, newer))
    }
}
