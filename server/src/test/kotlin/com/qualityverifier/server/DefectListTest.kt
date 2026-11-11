package com.qualityverifier.server

import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.reports.DefectList
import com.qualityverifier.text.fundiDefectCount
import com.qualityverifier.text.parseAssistantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The problems of an assessment, from its stored replies.
 *
 * The golden files under `src/test/resources/replies/` are whole sessions: an intake code,
 * then each assistant reply in order. Each `.expected` file lists the problems the session
 * must give, in order, with their labels and default part. Any change to the parser, to
 * `problemsOf`, or to the default-part table that moves one of them fails here.
 */
class DefectListTest {

    // ---------------------------------------------------------- golden files

    @Test
    fun `today's shape gives the verdict's defects in order`() = assertGolden("today_table_evaluation")

    @Test
    fun `the one-list shape gives the findings in order`() = assertGolden("one_list_sofa_evaluation")

    @Test
    fun `a diagnosis-only session gives its findings`() = assertGolden("diagnosis_only")

    @Test
    fun `a corrected verdict replaces the earlier list`() = assertGolden("today_corrected_verdict")

    @Test
    fun `a diagnosis-only session is tagged with its purpose`() {
        val session = Session.load("diagnosis_only")
        val list = session.defectList()
        assertEquals(FundiPurpose.DIAGNOSE, list.purpose)
        assertFalse("a diagnosis is not a full assessment", list.purpose!!.fullAssessment)
        assertEquals(2, list.problems.size)
    }

    @Test
    fun `with a verdict, the list is as long as the stored defect count`() {
        // The count and the list read the same newest verdict and newest diagnosis, so the
        // data API's problems and the portal's quality rates cannot disagree.
        listOf("today_table_evaluation", "one_list_sofa_evaluation", "today_corrected_verdict").forEach { name ->
            val session = Session.load(name)
            val count = fundiDefectCount(session.replies.asReversed().map(::parseAssistantContent))
            assertEquals(name, count, session.defectList().problems.size)
        }
    }

    @Test
    fun `without a verdict the count stays null and the problems are still listed`() {
        val session = Session.load("diagnosis_only")
        assertNull(fundiDefectCount(session.replies.asReversed().map(::parseAssistantContent)))
        assertTrue(session.defectList().problems.isNotEmpty())
    }

    // ------------------------------------------------------- the newest blocks

    @Test
    fun `a newest diagnosis written once wins over an older verdict that lists defects`() {
        // A session straddling the prompt change: the count takes the findings, so the
        // list must too, or the two disagree.
        val older = Session.load("today_table_evaluation").replies.last()
        val newer = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back left joint","area":"structural","severity":"moderate","dimension":"joint_fit","what_i_see":"A gap."}]}
            ```
        """.trimIndent()

        val list = DefectList.fromTexts(listOf(older, newer))

        assertEquals(listOf("Gap at the back left joint"), list.problems.map { it.title })
        assertEquals(1, fundiDefectCount(listOf(newer, older).map(::parseAssistantContent)))
    }

    @Test
    fun `a sound verdict lists the cosmetic finding beside it, as the count counts it`() {
        // Cosmetic findings count against first time clean (Nathan, 11 November 2026).
        val reply = """
            ```fb-diagnosis
            {"findings":[{"title":"Saw marks on the top","area":"surface","severity":"cosmetic","dimension":"finishing"}]}
            ```

            ```qv-verdict
            {"verdict":"sound","headline":"Good work","defects":[]}
            ```
        """.trimIndent()

        val list = DefectList.fromTexts(listOf(reply))

        assertFalse(list.unknown)
        assertEquals(listOf("Saw marks on the top"), list.problems.map { it.title })
        assertEquals(WorkPart.FINISHING, list.problems.single().defaultPart)
        assertEquals(1, fundiDefectCount(listOf(parseAssistantContent(reply))))
    }

    @Test
    fun `a sound verdict-only follow-up lists nothing, not the older diagnosis's findings`() {
        // Today's prompt: the follow-up verdict clears the earlier problems, and the count is 0.
        val older = Session.load("today_table_evaluation").replies.last()
        val sound = "```qv-verdict\n{\"verdict\":\"sound\",\"headline\":\"All fixed\",\"defects\":[]}\n```"

        val list = DefectList.fromTexts(listOf(older, sound))

        assertFalse(list.unknown)
        assertTrue(list.problems.isEmpty())
        assertEquals(0, fundiDefectCount(listOf(sound, older).map(::parseAssistantContent)))
    }

    @Test
    fun `a newest diagnosis that will not decode makes the problems unknown, not empty`() {
        val unreadable = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back left joint","severity":"moder
            ```
        """.trimIndent()
        val sound = "```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"Still one joint\"}\n```"

        val alone = DefectList.fromTexts(listOf(unreadable))
        assertTrue(alone.unknown)
        assertTrue(alone.problems.isEmpty())

        val besideVerdict = DefectList.fromTexts(listOf(unreadable + "\n\n" + sound))
        assertTrue(besideVerdict.unknown)
    }

    @Test
    fun `an unreadable diagnosis beside verdict defects leaves the defects`() {
        val reply = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint","defects":[{"title":"Gap at the joint","area":"structural","severity":"minor"}]}
            ```
        """.trimIndent()

        val list = DefectList.fromTexts(listOf(reply))

        assertFalse(list.unknown)
        assertEquals(listOf("Gap at the joint"), list.problems.map { it.title })
        // No finding to give a dimension, and structural alone gives no part.
        assertEquals("", list.problems.single().dimension)
        assertNull(list.problems.single().defaultPart)
    }

    @Test
    fun `a session with no blocks has no problems, and that is known`() {
        val list = DefectList.fromTexts(listOf("Send me a photo of the underside, please."))
        assertTrue(list.problems.isEmpty())
        assertFalse(list.unknown)
    }

    @Test
    fun `a Kagua verdict reads as its defects, placed by area`() {
        val reply = """
            ```qv-verdict
            {"verdict":"fair","headline":"Worth buying with care","defects":[
              {"title":"Torn seat cover","area":"upholstery","severity":"minor"},
              {"title":"Loose leg","area":"structural","severity":"serious"}
            ]}
            ```
        """.trimIndent()

        val list = DefectList.fromTexts(listOf(reply), purpose = null)

        assertNull(list.purpose)
        assertEquals(listOf(WorkPart.UPHOLSTERY, null), list.problems.map { it.defaultPart })
    }

    // ---------------------------------------------------------------- harness

    /** One golden session: its intake code and its assistant replies, oldest first. */
    private class Session(val intake: String?, val replies: List<String>) {
        fun defectList(): DefectList =
            DefectList.fromTexts(replies, FundiIntake.decode(intake)?.second)

        companion object {
            fun load(name: String): Session {
                val lines = resource("$name.txt").lines()
                val intake = lines.first().removePrefix("intake:").trim()
                val replies = mutableListOf<StringBuilder>()
                lines.drop(1).forEach { line ->
                    if (line.trim() == REPLY_SEPARATOR) replies += StringBuilder()
                    else replies.last().appendLine(line)
                }
                return Session(intake, replies.map { it.toString().trim() })
            }
        }
    }

    private fun assertGolden(name: String) {
        val expected = resource("$name.expected").lines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split("|").map { it.trim() } }
        val actual = Session.load(name).defectList().problems.map { problem ->
            listOf(
                problem.title,
                problem.area.ifBlank { "-" },
                problem.severity.ifBlank { "-" },
                problem.dimension.ifBlank { "-" },
                problem.defaultPart?.id ?: "-",
            )
        }
        assertEquals(name, expected, actual)
    }

    private companion object {
        const val REPLY_SEPARATOR = "--- reply ---"

        fun resource(path: String): String {
            val url = DefectListTest::class.java.getResource("/replies/$path")
                ?: error("no test reply at src/test/resources/replies/$path")
            return url.readText()
        }
    }
}
