package com.qualityverifier.server

import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.reports.EVALUATION_HEADER
import com.qualityverifier.server.reports.ListedProblem
import com.qualityverifier.server.reports.NamedWorker
import com.qualityverifier.server.reports.PROBLEM_HEADER
import com.qualityverifier.server.reports.StoredSession
import com.qualityverifier.server.reports.Unlinked
import com.qualityverifier.server.reports.csvRow
import com.qualityverifier.server.reports.evaluationRow
import com.qualityverifier.server.reports.linkProblems
import com.qualityverifier.server.reports.problemRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Whether each problem reaches a worker named in "Who worked on this?", and why not.
 *
 * The study's measure of a worker's skill is built from these links, so each reason is
 * pinned: the pilot reads the reasons to decide whether the part list or the picker has to
 * change before the RCT.
 */
class ProblemLinkageTest {

    @Test
    fun `a worker on the problem's part is linked`() {
        val links = linkProblems(listOf(JOINT), listOf(NamedWorker("w1", "joinery")))
        assertNull(links.single().unlinked)
    }

    @Test
    fun `a whole-piece worker is linked to every part`() {
        val links = linkProblems(listOf(JOINT, FINISH, SEAT), listOf(NamedWorker("w1", null)))
        assertEquals(listOf(true, true, true), links.map { it.linked })
    }

    @Test
    fun `the manager counts as a named worker`() {
        // "Me" is stored with no worker id.
        val links = linkProblems(listOf(JOINT), listOf(NamedWorker(null, "joinery")))
        assertNull(links.single().unlinked)
    }

    @Test
    fun `nobody named is the first reason`() {
        val links = linkProblems(listOf(JOINT, KNOT), emptyList())
        assertEquals(listOf(Unlinked.NOBODY_NAMED, Unlinked.NOBODY_NAMED), links.map { it.unlinked })
    }

    @Test
    fun `a worker named only on other is never linked`() {
        val links = linkProblems(
            listOf(JOINT, KNOT),
            listOf(NamedWorker("w1", "other"), NamedWorker("w2", "other")),
        )
        assertEquals(listOf(Unlinked.ONLY_OTHER, Unlinked.ONLY_OTHER), links.map { it.unlinked })
    }

    @Test
    fun `a worker on other beside one on the right part still links`() {
        val links = linkProblems(
            listOf(JOINT),
            listOf(NamedWorker("w1", "other"), NamedWorker("w2", "joinery")),
        )
        assertNull(links.single().unlinked)
    }

    @Test
    fun `a problem with no part reaches nobody, even a whole-piece worker`() {
        val links = linkProblems(listOf(KNOT), listOf(NamedWorker("w1", null)))
        assertEquals(Unlinked.NO_PART, links.single().unlinked)
    }

    @Test
    fun `a worker on another part is a mismatch`() {
        val links = linkProblems(
            listOf(JOINT, FINISH),
            listOf(NamedWorker("w1", "finishing"), NamedWorker("w2", "frame")),
        )
        assertEquals(listOf(Unlinked.PART_MISMATCH, null), links.map { it.unlinked })
    }

    @Test
    fun `a stored part is compared trimmed and lowercased`() {
        val links = linkProblems(listOf(SEAT), listOf(NamedWorker("w1", " Upholstery")))
        assertNull(links.single().unlinked)
    }

    @Test
    fun `an evaluation row gives the share linked and each reason's count`() {
        val session = session(TODAY_THREE_PROBLEMS)
        val row = evaluationRow(session, listOf(NamedWorker("w1", "joinery")))

        assertEquals(EVALUATION_HEADER.size, row.size)
        val byName = EVALUATION_HEADER.zip(row).toMap()
        assertEquals(3, byName["problems"])
        assertEquals(1, byName["linked"])
        assertEquals("0.333", byName["share_linked"])
        assertEquals(1, byName["no_part"])
        assertEquals(1, byName["part_mismatch"])
        assertEquals(0, byName["nobody_named"])
        assertEquals("evaluate", byName["purpose"])
        assertEquals("en", byName["language"])
        assertEquals(false, byName["whole_piece_named"])
    }

    @Test
    fun `an evaluation whose problems are unknown leaves the counts blank`() {
        val session = session("```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\n```")
        val byName = EVALUATION_HEADER.zip(evaluationRow(session, emptyList())).toMap()
        assertEquals(true, byName["problems_unknown"])
        assertEquals("", byName["problems"])
        assertEquals("", byName["share_linked"])
    }

    @Test
    fun `a problem row names its reason`() {
        val rows = problemRows(session(TODAY_THREE_PROBLEMS), listOf(NamedWorker("w1", "joinery")))
        assertEquals(3, rows.size)
        rows.forEach { assertEquals(PROBLEM_HEADER.size, it.size) }
        val reasons = rows.map { PROBLEM_HEADER.zip(it).toMap()["reason"] }
        assertEquals(listOf(null, "no_part", "part_mismatch"), reasons)
    }

    @Test
    fun `a CSV field is quoted only when it has to be`() {
        assertEquals(
            "plain,\"a, b\",\"say \"\"hi\"\"\",,true",
            csvRow(listOf("plain", "a, b", "say \"hi\"", null, true)),
        )
    }

    private fun session(reply: String) = StoredSession(
        id = "6c0e2f1a-0000-4000-8000-000000000001",
        userId = "6c0e2f1a-0000-4000-8000-000000000002",
        pieceId = null,
        itemTypeId = "wooden-table",
        createdAt = 1_762_772_602_000,
        intakeAnswers = "fundi-en-evaluate",
        byTester = false,
        deletedByUser = false,
        assistantTexts = listOf(reply),
    )

    private companion object {
        val JOINT = ListedProblem("Gap at the joint", "structural", "moderate", "joint_fit", WorkPart.JOINERY)
        val FINISH = ListedProblem("Saw marks", "surface", "cosmetic", "finishing", WorkPart.FINISHING)
        val SEAT = ListedProblem("Torn cover", "upholstery", "minor", "", WorkPart.UPHOLSTERY)
        val KNOT = ListedProblem("Knot near the edge", "material", "minor", "", null)

        /** Joinery, then a material problem, then a finishing one. */
        val TODAY_THREE_PROBLEMS = """
            ```fb-diagnosis
            {"findings":[
              {"title":"Gap at the joint","area":"structural","severity":"moderate","dimension":"joint_fit"},
              {"title":"Saw marks","area":"surface","severity":"cosmetic","dimension":"finishing"}
            ]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint","defects":[
              {"title":"Gap at the joint","area":"structural","severity":"moderate"},
              {"title":"Knot near the edge","area":"material","severity":"minor"},
              {"title":"Saw marks","area":"surface","severity":"cosmetic"}
            ]}
            ```
        """.trimIndent()
    }
}
