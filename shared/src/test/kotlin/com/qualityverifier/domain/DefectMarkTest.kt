package com.qualityverifier.domain

import com.qualityverifier.text.parseAssistantContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a defect shows (v0.6.0, row 19): the photo and the point Claude gives for a finding.
 * A position written badly costs the mark and never the diagnosis, which is the maker's
 * whole answer.
 */
class DefectMarkTest {

    private fun diagnosis(where: String): Diagnosis? = parseAssistantContent(
        "```fb-diagnosis\n" +
            """{"language":"en","findings":[{"title":"Gap at the back left joint","severity":"moderate"$where}]}""" +
            "\n```",
    ).diagnosis

    @Test
    fun `a finding's where is read as its mark`() {
        val finding = diagnosis(""","where":{"photo":3,"x":0.42,"y":0.31}""")!!.findings.single()
        assertEquals(DefectMark(3, 0.42, 0.31), finding.mark)
    }

    @Test
    fun `a quoted number still reads`() {
        val finding = diagnosis(""","where":{"photo":"2","x":"0.5","y":0.5}""")!!.findings.single()
        assertEquals(DefectMark(2, 0.5, 0.5), finding.mark)
    }

    @Test
    fun `a bad position costs the mark and keeps the finding`() {
        listOf(
            ""","where":"the left leg"""",
            ""","where":{"photo":0,"x":0.5,"y":0.5}""",
            ""","where":{"photo":2,"x":1.4,"y":0.5}""",
            ""","where":{"photo":2,"x":0.5}""",
            ""","where":[2,0.5,0.5]""",
            "",
        ).forEach { where ->
            val finding = diagnosis(where)!!.findings.single()
            assertEquals(where, "Gap at the back left joint", finding.title)
            assertNull(where, finding.mark)
        }
    }
}
