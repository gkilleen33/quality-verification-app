package com.qualityverifier.server

import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.LineBlock
import com.qualityverifier.domain.PhotosBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.TextBlock
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.ReportFixtures.A_DAY
import com.qualityverifier.server.ReportFixtures.DAY_ONE
import com.qualityverifier.server.ReportFixtures.KAMAU
import com.qualityverifier.server.ReportFixtures.OTIENO
import com.qualityverifier.server.ReportFixtures.session
import com.qualityverifier.server.ReportFixtures.sha
import com.qualityverifier.server.ReportFixtures.worker
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.reports.PhotoPlan
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
 * What each reader gets (plan v7 §5.2), on the blocks the PDF is drawn from.
 *
 * The customer report is the one with something to leave out: nothing addressed to the
 * maker, no worker name, no part. Those are pinned here against sessions that have all of
 * them, so a later kind cannot leak them into this one.
 */
class ReportComposerTest {

    private val composer = ReportComposer()

    private val workers = listOf(
        worker(KAMAU, "Kamau", WorkPart.JOINERY),
        worker(OTIENO, "Otieno", WorkPart.FINISHING),
        worker(null, null, null),
    )

    private fun compose(
        kind: ReportKind,
        sessions: List<ReportSession>,
        makerName: String? = "Wanjirũ",
    ): ReportContent = composer.compose(
        ReportInput(
            kind = kind,
            piece = ReportFixtures.piece,
            assessments = sessions.map(ReportAssessment::of),
            makerName = makerName,
            madeAt = DAY_ONE + 3 * A_DAY,
        ),
    )

    private fun ReportContent.text(): String = blocks.joinToString("\n") { block ->
        when (block) {
            is HeadingBlock -> block.text
            is TextBlock -> block.text
            is LineBlock -> "${block.label}: ${block.value}"
            is ProblemBlock -> listOfNotNull(block.title, block.severity, block.part, block.whatISee, block.whatItMeans, block.whatToDo).joinToString(" | ")
            is PhotosBlock -> "photos ${block.sha256.size}"
            else -> block.toString()
        }
    }

    @Test
    fun `a customer report covers every assessment in date order with its date`() {
        val content = compose(
            ReportKind.CUSTOMER,
            listOf(
                session("s2", DAY_ONE + 2 * A_DAY, listOf(ReportFixtures.ALL_FIXED)),
                session("s1", DAY_ONE, ReportFixtures.TABLE),
            ).sortedBy { it.createdAt },
        )

        val text = content.text()
        val first = text.indexOf("Assessment of 12 Nov 2026")
        val second = text.indexOf("Assessment of 14 Nov 2026")
        assertTrue(text, first >= 0 && second > first)
        assertEquals(listOf("s1", "s2"), content.sessionIds)
        assertTrue(text, text.contains("Verdict: Fair — Strong table, one joint to close"))
        assertTrue(text, text.contains("Verdict: Sound — All put right"))
        // The newest verdict closes it.
        assertTrue(text, text.substringAfter("Latest verdict").contains("Sound — All put right"))
        assertTrue(text, text.endsWith("Made with Fundi Bora"))
    }

    @Test
    fun `a customer report contains no worker name and no part word even when the sessions have both`() {
        val content = compose(
            ReportKind.CUSTOMER,
            listOf(session("s1", DAY_ONE, ReportFixtures.TABLE, workers = workers)),
        )

        val text = content.text()
        listOf("Kamau", "Otieno", "Report made by", "Worked on").forEach { assertFalse(it, text.contains(it)) }
        // The maker's name is in the piece's label, which the customer does see; it is not
        // printed anywhere else.
        assertEquals(1, Regex("Wanjirũ").findAll(text).count())
        WorkPart.entries.forEach { part ->
            assertFalse(part.id, Regex("\\b${part.id.replaceFirstChar(Char::uppercase)}\\b").containsMatchIn(text))
        }
        assertTrue(content.printedNames.isEmpty())
        assertTrue(content.snapshot.workers.isEmpty())
        assertNull(content.snapshot.madeBy)
        content.blocks.filterIsInstance<ProblemBlock>().forEach { assertNull(it.part) }
    }

    @Test
    fun `a customer report prints what describes the piece and nothing addressed to the maker`() {
        val content = compose(ReportKind.CUSTOMER, listOf(session("s1", DAY_ONE, ReportFixtures.TABLE)))

        val problems = content.blocks.filterIsInstance<ProblemBlock>()
        assertEquals(4, problems.size)
        val gap = problems.first()
        assertEquals("Gap at the back left joint", gap.title)
        assertEquals("Moderate", gap.severity)
        assertEquals("There is a gap at the back joint.", gap.whatISee)
        assertEquals("The joint will loosen.", gap.whatItMeans)
        assertNull("what to do is for the maker", gap.whatToDo)
        val text = content.text()
        assertFalse("the summary is for the maker", text.contains("Close the back joint and flatten the top"))
        assertFalse("unverified is for the maker", text.contains("inside of the joints"))
        assertFalse(text.contains("fixed"))
    }

    @Test
    fun `an assessment with no verdict prints its date and No full verdict`() {
        val unfinished = session("s0", DAY_ONE - A_DAY, replies = listOf("Send me one photo of the whole piece."), messageCount = 1)
        val content = compose(ReportKind.CUSTOMER, listOf(unfinished, session("s1", DAY_ONE, ReportFixtures.TABLE)))

        val text = content.text()
        assertTrue(text, text.contains("Assessment of 11 Nov 2026 (Not finished)"))
        assertTrue(text, text.contains("No full verdict"))
        assertEquals(listOf("s0", "s1"), content.sessionIds)
        // The unfinished one says nothing about problems: "none recorded" would overstate it.
        assertFalse(text.substringBefore("Assessment of 12 Nov").contains("No problems recorded"))
    }

    @Test
    fun `a reply in each format renders every problem once`() {
        val content = compose(
            ReportKind.CUSTOMER,
            listOf(
                session("today", DAY_ONE, ReportFixtures.TABLE),
                session("one-list", DAY_ONE + A_DAY, ReportFixtures.SOFA, verdictLanguage = "sw"),
            ),
        )

        val titles = content.blocks.filterIsInstance<ProblemBlock>().map { it.title }
        assertEquals(8, titles.size)
        assertEquals(titles.size, titles.toSet().size)
        assertTrue(titles.contains("Kiungo cha reli ya nyuma kimefunguka"))
        assertTrue(titles.contains("Knot near the front edge"))
        // One list, so the one-list reply's consequence fields are printed too.
        val reli = content.blocks.filterIsInstance<ProblemBlock>().first { it.title.startsWith("Kiungo") }
        assertEquals("Reli ya nyuma inasogea ukiisukuma.", reli.whatISee)
        assertEquals(8, content.defectRefs.size)
        assertEquals(8, content.snapshot.problems.size)
        assertTrue(content.defectRefs.all { it.startsWith("today-r1:") || it.startsWith("one-list-r0:") })
    }

    @Test
    fun `the report follows the language of the newest verdict`() {
        val content = compose(
            ReportKind.CUSTOMER,
            listOf(
                session("today", DAY_ONE, ReportFixtures.TABLE),
                session("one-list", DAY_ONE + A_DAY, ReportFixtures.SOFA, verdictLanguage = "sw"),
            ),
        )
        assertEquals("sw", content.labels.code)
        assertTrue(content.text().contains("Ripoti ya ubora"))

        val english = compose(ReportKind.CUSTOMER, listOf(session("today", DAY_ONE, ReportFixtures.TABLE)))
        assertEquals("en", english.labels.code)
    }

    @Test
    fun `the snapshot records each assessment's level and headline and each problem's reference`() {
        val content = compose(ReportKind.CUSTOMER, listOf(session("s1", DAY_ONE, ReportFixtures.TABLE)))

        val assessment = content.snapshot.assessments.single()
        assertEquals("s1", assessment.sessionId)
        assertEquals("fair", assessment.level)
        assertEquals("Strong table, one joint to close", assessment.headline)
        val problem = content.snapshot.problems.first()
        assertEquals("s1-r1", problem.messageId)
        assertEquals(0, problem.ordinal)
        assertEquals("Gap at the back left joint", problem.title)
        assertEquals("moderate", problem.severity)
        assertEquals(listOf("s1-r1:0", "s1-r1:1", "s1-r1:2", "s1-r1:3"), content.defectRefs)
    }

    @Test
    fun `the opening photo of each assessment comes first, then the rest, up to eight`() {
        val plan = PhotoPlan.of(
            listOf(
                listOf("a1", "a2", "a3", "a4", "a5"),
                listOf("b1", "b2", "b3", "b4", "b5"),
                listOf("c1", "c2"),
            ),
        )
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5"), plan.forAssessment(0))
        assertEquals(listOf("b1", "b2"), plan.forAssessment(1))
        assertEquals(listOf("c1"), plan.forAssessment(2))

        val content = compose(
            ReportKind.CUSTOMER,
            listOf(
                session("s1", DAY_ONE, ReportFixtures.TABLE, photos = (1..6).map { sha("s1-$it") }),
                session("s2", DAY_ONE + A_DAY, listOf(ReportFixtures.ALL_FIXED), photos = (1..6).map { sha("s2-$it") }),
            ),
        )
        assertEquals(8, content.photos.size)
        assertEquals(sha("s2-1"), content.blocks.filterIsInstance<PhotosBlock>()[1].sha256.first())
    }

    @Test
    fun `the preview is the same blocks the PDF is drawn from`() {
        val content = compose(ReportKind.CUSTOMER, listOf(session("s1", DAY_ONE, ReportFixtures.TABLE, photos = listOf(sha("p")))))
        val preview = content.preview()
        assertEquals(content.blocks, preview.blocks)
        assertEquals("customer", preview.kind)
        assertEquals("Kiti cha Wanjirũ", preview.pieceName)
        assertEquals(1, preview.photoCount)
    }
}
