package com.qualityverifier.server

import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.LineBlock
import com.qualityverifier.domain.NamesBlock
import com.qualityverifier.domain.NoteBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.TextBlock
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.ReportFixtures.A_DAY
import com.qualityverifier.server.ReportFixtures.DAY_ONE
import com.qualityverifier.server.ReportFixtures.KAMAU
import com.qualityverifier.server.ReportFixtures.OTIENO
import com.qualityverifier.server.ReportFixtures.session
import com.qualityverifier.server.ReportFixtures.worker
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.db.ReportWorker
import com.qualityverifier.server.reports.PdfRenderer
import com.qualityverifier.server.reports.PhotoSource
import com.qualityverifier.server.reports.ReportAssessment
import com.qualityverifier.server.reports.ReportComposer
import com.qualityverifier.server.reports.ReportContent
import com.qualityverifier.server.reports.ReportInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The worker and Me reports (plan v7 §5.2 and PR 4; Nathan, 12 November 2026): which
 * problems go under which part, which names go under which heading, and what a fixes
 * report says and never says.
 *
 * The table session's problems place as: the gap (joint fit) on joinery, the top (frame
 * squareness) on the frame, the saw marks (finishing) on finishing, and the knot
 * (material) on no part.
 */
class PartReportsTest {

    private val composer = ReportComposer()

    private val first = session(
        "s1", DAY_ONE, ReportFixtures.TABLE,
        workers = listOf(worker(KAMAU, "Kamau", WorkPart.JOINERY), worker(OTIENO, "Otieno", WorkPart.FINISHING)),
    )

    /** The re-assessment: all put right, with a different joiner this time and the manager on the whole piece. */
    private val second = session(
        "s2", DAY_ONE + A_DAY, listOf(ReportFixtures.ALL_FIXED),
        workers = listOf(worker(OTIENO, "Otieno", WorkPart.JOINERY), worker(null, null, null)),
    )

    private fun compose(
        kind: ReportKind,
        sessions: List<ReportSession>,
        chosen: String,
        parts: List<WorkPart> = emptyList(),
        previous: String? = null,
        makerName: String? = "Wanjirũ",
        itemType: String = "wooden-table",
    ): ReportContent {
        val assessments = sessions.sortedBy { it.createdAt }.map(ReportAssessment::of)
        return composer.compose(
            ReportInput(
                kind = kind,
                piece = ReportFixtures.piece.copy(itemTypeId = itemType),
                assessments = assessments,
                makerName = makerName,
                madeAt = DAY_ONE + 3 * A_DAY,
                chosen = assessments.first { it.sessionId == chosen },
                previous = previous?.let { id -> assessments.first { it.sessionId == id } },
                parts = parts,
            ),
        )
    }

    private fun ReportContent.text(): String = blocks.joinToString("\n") { block ->
        when (block) {
            is HeadingBlock -> block.text
            is TextBlock -> block.text
            is NoteBlock -> block.text
            is LineBlock -> "${block.label}: ${block.value}"
            is NamesBlock -> "${block.label} ${block.names.joinToString(", ")}"
            is ProblemBlock -> listOfNotNull(block.title, block.severity, block.part, block.whatISee, block.whatItMeans, block.whatToDo).joinToString(" | ")
            else -> ""
        }
    }

    private fun ReportContent.titles() = blocks.filterIsInstance<ProblemBlock>().map { it.title }

    @Test
    fun `a part issues report carries only the problems on the chosen parts, with what to do`() {
        val joinery = compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(WorkPart.JOINERY))

        assertEquals(listOf("Gap at the back left joint"), joinery.titles())
        val gap = joinery.blocks.filterIsInstance<ProblemBlock>().single()
        assertEquals("Take it apart, glue it and clamp it.", gap.whatToDo)
        assertEquals("Joinery", gap.part)
        val text = joinery.text()
        assertTrue(text, text.contains("Verdict: Fair — Strong table, one joint to close"))
        assertTrue("the summary is printed for a worker", text.contains("Summary: Close the back joint and flatten the top"))
        assertEquals(listOf("s1-r1:0"), joinery.defectRefs)
        assertEquals(listOf("s1"), joinery.sessionIds)

        val two = compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(WorkPart.FRAME, WorkPart.FINISHING))
        assertEquals(listOf("Top not flat across the width", "Saw marks on the top"), two.titles())
    }

    @Test
    fun `a problem with no part is in no part report and under Part not given on the full report`() {
        WorkPart.entries.forEach { part ->
            val report = compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(part))
            assertFalse(part.id, report.titles().contains("Knot near the front edge"))
        }
        val full = compose(ReportKind.FULL, listOf(first), chosen = "s1")
        val text = full.text()
        assertTrue(text, text.contains("Knot near the front edge"))
        assertTrue(text, text.substringAfter("Part not given").contains("Knot near the front edge"))
        assertEquals(4, full.titles().size)
        // Grouped by part, in the picker's order, the unplaced last.
        val headings = full.blocks.filterIsInstance<HeadingBlock>().filter { it.level == 3 }.map { it.text }
        assertEquals(listOf("Frame", "Joinery", "Finishing", "Part not given"), headings)
    }

    @Test
    fun `a chosen part with no problem prints the record line, not a judgement`() {
        val report = compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(WorkPart.UPHOLSTERY))
        val text = report.text()
        assertTrue(text, text.contains("No issues recorded on this part in this assessment"))
        assertTrue(report.titles().isEmpty())
        assertFalse(text.contains("sound"))
    }

    @Test
    fun `names go under the part they were recorded on, and nowhere else`() {
        val joinery = compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(WorkPart.JOINERY))
        val text = joinery.text()
        assertTrue(text, text.contains("Worked on this part: Kamau"))
        assertFalse(text, text.contains("Otieno"))
        assertEquals(listOf("Kamau", "Wanjirũ"), joinery.printedNames)
        val row = joinery.snapshot.workers.single()
        assertEquals(KAMAU, row.workerId)
        assertEquals("joinery", row.part)

        // Two on one part: both. A part with nobody: no line at all.
        val both = first.copy(workers = first.workers + worker("c0ffee00-0000-4000-8000-0000000000a3", "Njeri", WorkPart.JOINERY))
        assertTrue(compose(ReportKind.PART_ISSUES, listOf(both), chosen = "s1", parts = listOf(WorkPart.JOINERY)).text().contains("Worked on this part: Kamau, Njeri"))
        assertFalse(compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(WorkPart.FRAME)).text().contains("Worked on"))

        // The customer report of the same piece carries neither name.
        val customer = composer.compose(
            ReportInput(ReportKind.CUSTOMER, ReportFixtures.piece, listOf(ReportAssessment.of(first)), null, DAY_ONE),
        ).text()
        assertFalse(customer.contains("Kamau"))
        assertFalse(customer.contains("Otieno"))

        // No count and no score anywhere.
        listOf(joinery.text(), customer).forEach { assertFalse(it, Regex("\\d+ (problems|issues) ").containsMatchIn(it)) }
    }

    @Test
    fun `a whole-piece worker is on the full report's own line, and on a part report only when it covers every part`() {
        val wholePiece = first.copy(workers = first.workers + worker("c0ffee00-0000-4000-8000-0000000000a4", "Achieng", null))

        val full = compose(ReportKind.FULL, listOf(wholePiece), chosen = "s1")
        val text = full.text()
        assertTrue(text, text.contains("Also worked on this piece: Achieng"))
        assertTrue(text, text.indexOf("Also worked on this piece") < text.indexOf("Frame"))
        assertFalse(text.contains("Worked on this part: Achieng"))

        val onePart = compose(ReportKind.PART_ISSUES, listOf(wholePiece), chosen = "s1", parts = listOf(WorkPart.JOINERY))
        assertFalse(onePart.text().contains("Achieng"))

        // A table offers every part but upholstery; naming them all covers the piece.
        val every = compose(
            ReportKind.PART_ISSUES, listOf(wholePiece), chosen = "s1",
            parts = listOf(WorkPart.FRAME, WorkPart.JOINERY, WorkPart.FINISHING, WorkPart.ASSEMBLY, WorkPart.OTHER),
        )
        assertTrue(every.text().contains("Worked on this part: Kamau, Achieng"))
    }

    @Test
    fun `the manager's own row prints the account's name, and a cleared name prints nothing`() {
        val full = compose(ReportKind.FULL, listOf(first, second), chosen = "s2")
        assertTrue(full.text(), full.text().contains("Also worked on this piece: Wanjirũ"))

        val cleared = second.copy(workers = listOf(ReportWorker(KAMAU, WorkPart.JOINERY, name = null)))
        val report = compose(ReportKind.PART_ISSUES, listOf(first, cleared), chosen = "s2", parts = listOf(WorkPart.JOINERY))
        assertFalse(report.text().contains("Worked on"))
    }

    @Test
    fun `every report but the customer's says who made it`() {
        listOf(
            compose(ReportKind.PART_ISSUES, listOf(first), chosen = "s1", parts = listOf(WorkPart.JOINERY)),
            compose(ReportKind.PART_FIXES, listOf(first, second), chosen = "s2", previous = "s1", parts = listOf(WorkPart.JOINERY)),
            compose(ReportKind.FULL, listOf(first), chosen = "s1"),
        ).forEach { report ->
            assertTrue(report.kind.id, report.text().contains("Report made by Wanjirũ"))
            assertEquals(report.kind.id, "Wanjirũ", report.snapshot.madeBy)
            assertTrue(report.printedNames.contains("Wanjirũ"))
        }
        val customer = composer.compose(
            ReportInput(ReportKind.CUSTOMER, ReportFixtures.piece, listOf(ReportAssessment.of(first)), "Wanjirũ", DAY_ONE),
        )
        assertFalse(customer.text().contains("Report made by"))
    }

    @Test
    fun `a fixes report prints last time and this time on each part, the note, and never the word fixed`() {
        val fixes = compose(
            ReportKind.PART_FIXES, listOf(first, second), chosen = "s2", previous = "s1",
            parts = listOf(WorkPart.JOINERY, WorkPart.FRAME),
        )
        val text = fixes.text()
        assertTrue(text, text.contains("Assessment of 13 Nov 2026"))
        assertTrue(text, text.contains("Found in the assessment of 12 Nov 2026"))
        assertTrue(text, text.contains("Found this time"))
        // Last time's gap under joinery, then this time's nothing recorded.
        val joinery = text.substringAfter("Joinery").substringBefore("Frame")
        assertTrue(joinery, joinery.substringAfter("Found in the assessment").substringBefore("Found this time").contains("Gap at the back left joint"))
        assertTrue(joinery, joinery.substringAfter("Found this time").contains("No issues recorded on this part in this assessment"))
        assertTrue(text, text.contains("An issue missing from this assessment may have been put right"))
        assertFalse(text.lowercase().contains("fixed"))
        // The names are the re-assessment's: Otieno did the joinery this time.
        assertTrue(text, text.contains("Worked on this part (this assessment): Otieno"))
        assertFalse(text.contains("Worked on this part: Kamau"))
        assertEquals(listOf("s1", "s2"), fixes.sessionIds)
        assertEquals(2, fixes.snapshot.assessments.size)
        assertEquals(listOf("s1-r1:0", "s1-r1:1"), fixes.defectRefs)
    }

    @Test
    fun `the chosen assessment decides, unchanged by a later one`() {
        val onFirst = compose(ReportKind.PART_ISSUES, listOf(first, second), chosen = "s1", parts = listOf(WorkPart.JOINERY))
        assertEquals(listOf("Gap at the back left joint"), onFirst.titles())
        assertTrue(onFirst.text().contains("Worked on this part: Kamau"))

        val onSecond = compose(ReportKind.PART_ISSUES, listOf(first, second), chosen = "s2", parts = listOf(WorkPart.JOINERY))
        assertTrue(onSecond.titles().isEmpty())
        assertTrue(onSecond.text().contains("Verdict: Sound — All put right"))
    }

    @Test
    fun `a full report of an assessment whose problems cannot be read says so`() {
        val unreadable = session("s3", DAY_ONE, listOf("```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap"))
        val full = compose(ReportKind.FULL, listOf(unreadable), chosen = "s3")
        assertTrue(full.text().contains("could not be read"))
        assertTrue(full.defectRefs.isEmpty())
    }

    @Test
    fun `the three kinds render as PDFs with their headings`() {
        val renderer = PdfRenderer()
        listOf(
            "part_issues_joinery.pdf" to compose(ReportKind.PART_ISSUES, listOf(first, second), chosen = "s1", parts = listOf(WorkPart.JOINERY, WorkPart.FINISHING)),
            "part_fixes_joinery.pdf" to compose(ReportKind.PART_FIXES, listOf(first, second), chosen = "s2", previous = "s1", parts = listOf(WorkPart.JOINERY)),
            "full.pdf" to compose(ReportKind.FULL, listOf(first), chosen = "s1"),
        ).forEach { (name, content) ->
            val pdf = renderer.render(content, PhotoSource { null })
            val text = ReportFixtures.text(pdf)
            assertTrue(name, text.contains("Report made by Wanjirũ"))
            assertTrue(name, text.contains("Worked on this part"))
            ReportFixtures.save(name, pdf)
        }
        assertNull(compose(ReportKind.FULL, listOf(first), chosen = "s1").blocks.filterIsInstance<ProblemBlock>().firstOrNull { it.whatToDo == null })
    }
}
