package com.qualityverifier.server

import com.qualityverifier.server.reports.CoverageShape
import com.qualityverifier.server.reports.CoverageSummary
import com.qualityverifier.server.reports.coverageOf
import com.qualityverifier.text.parseAssistantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How far today's two lists of problems disagree: the evidence for writing each problem
 * once, and the baseline for the defect count's change of meaning.
 */
class DefectCoverageTest {

    private fun coverage(vararg repliesOldestFirst: String) =
        coverageOf(repliesOldestFirst.reversed().map(::parseAssistantContent))

    @Test
    fun `matching lists have nothing unmatched`() {
        val result = coverage(matching)
        assertEquals(CoverageShape.TODAY, result.shape)
        assertEquals(2, result.defects)
        assertEquals(2, result.findings)
        assertEquals(0, result.defectsWithoutFinding)
        assertEquals(0, result.findingsWithoutDefect)
        assertFalse(result.lengthsDiffer)
    }

    @Test
    fun `a defect with no finding of its title is counted, and the lengths differ`() {
        val result = coverage(mismatched)
        assertEquals(CoverageShape.TODAY, result.shape)
        assertEquals(3, result.defects)
        assertEquals(2, result.findings)
        // "Knot near the edge" has no finding.
        assertEquals(1, result.defectsWithoutFinding)
        assertEquals(0, result.findingsWithoutDefect)
        assertTrue(result.lengthsDiffer)
    }

    @Test
    fun `titles match whatever their case and spacing, and a blank title matches nothing`() {
        val reply = """
            ```fb-diagnosis
            {"findings":[{"title":"Gap at the joint"},{"title":""}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"h","defects":[{"title":" gap AT the joint"},{"title":""}]}
            ```
        """.trimIndent()
        val result = coverage(reply)
        assertEquals(1, result.defectsWithoutFinding)
        assertEquals(1, result.findingsWithoutDefect)
        assertFalse(result.lengthsDiffer)
    }

    @Test
    fun `a clean diagnosis beside verdict defects leaves every defect unmatched`() {
        val reply = """
            ```fb-diagnosis
            {"findings":[]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"h","defects":[{"title":"Loose leg"}]}
            ```
        """.trimIndent()
        val result = coverage(reply)
        assertEquals(CoverageShape.TODAY, result.shape)
        assertEquals(1, result.defectsWithoutFinding)
        assertTrue(result.lengthsDiffer)
    }

    @Test
    fun `the newest verdict is compared with the newest diagnosis`() {
        val followUp = """
            ```qv-verdict
            {"verdict":"fair","headline":"h","defects":[{"title":"Gap at the joint"}]}
            ```
        """.trimIndent()
        // The follow-up's verdict keeps only the joint; the diagnosis is still the first
        // reply's, so its saw marks now have no defect.
        val result = coverage(mismatched, followUp)
        assertEquals(1, result.defects)
        assertEquals(2, result.findings)
        assertEquals(0, result.defectsWithoutFinding)
        assertEquals(1, result.findingsWithoutDefect)
    }

    @Test
    fun `other shapes are named and not compared`() {
        assertEquals(CoverageShape.NO_VERDICT, coverage("```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\"}]}\n```").shape)
        assertEquals(
            CoverageShape.NO_DIAGNOSIS,
            coverage("```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"h\",\"defects\":[{\"title\":\"Gap\"}]}\n```").shape,
        )
        assertEquals(
            CoverageShape.DIAGNOSIS_UNREADABLE,
            coverage("```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\n```\n\n```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"h\"}\n```").shape,
        )
        val oneList = coverage(
            "```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\",\"what_i_see\":\"A gap.\"}]}\n```\n\n" +
                "```qv-verdict\n{\"verdict\":\"fair\",\"headline\":\"h\"}\n```",
        )
        assertEquals(CoverageShape.ONE_LIST, oneList.shape)
        assertEquals(0, oneList.defectsWithoutFinding)
        assertFalse(oneList.lengthsDiffer)
    }

    @Test
    fun `the summary counts full assessments only, and their totals in today's shape`() {
        val summary = CoverageSummary.of(
            listOf(
                true to coverage(matching),
                true to coverage(mismatched),
                // A diagnosis: not a full assessment, left out of the totals.
                false to coverage(mismatched),
                // A purpose this server cannot read: counted apart.
                null to coverage(mismatched),
                true to coverage("```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\"}]}\n```"),
            ),
        )
        val rows = summary.rows().associate { it[0] to it[1] }

        assertEquals(5, rows["sessions"])
        assertEquals(1, rows["purpose_not_known"])
        assertEquals(3, rows["full_assessments"])
        assertEquals(2, rows["full_assessments_today"])
        assertEquals(1, rows["full_assessments_no_verdict"])
        assertEquals(0, rows["full_assessments_one_list"])
        assertEquals(1, rows["today_lengths_differ"])
        assertEquals(5, rows["today_verdict_defects"])
        assertEquals(1, rows["today_defects_without_finding"])
        assertEquals(4, rows["today_findings"])
        assertEquals(0, rows["today_findings_without_defect"])
    }

    private val matching = """
        ```fb-diagnosis
        {"findings":[
          {"title":"Gap at the joint","area":"structural","dimension":"joint_fit"},
          {"title":"Saw marks","area":"surface","dimension":"finishing"}
        ]}
        ```

        ```qv-verdict
        {"verdict":"fair","headline":"One joint","defects":[
          {"title":"Gap at the joint","area":"structural"},
          {"title":"Saw marks","area":"surface"}
        ]}
        ```
    """.trimIndent()

    private val mismatched = """
        ```fb-diagnosis
        {"findings":[
          {"title":"Gap at the joint","area":"structural","dimension":"joint_fit"},
          {"title":"Saw marks","area":"surface","dimension":"finishing"}
        ]}
        ```

        ```qv-verdict
        {"verdict":"fair","headline":"One joint","defects":[
          {"title":"Gap at the joint","area":"structural"},
          {"title":"Knot near the edge","area":"material"},
          {"title":"Saw marks","area":"surface"}
        ]}
        ```
    """.trimIndent()
}
