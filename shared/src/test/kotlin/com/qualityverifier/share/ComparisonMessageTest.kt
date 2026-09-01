package com.qualityverifier.share

import com.qualityverifier.domain.Defect
import com.qualityverifier.domain.Verdict
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildComparisonRequest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The turn that asks for two pieces to be compared.
 *
 * What travels is the earlier piece's recorded findings, in the customer's own language,
 * because it is their message: they can read what was sent on their behalf.
 */
class ComparisonMessageTest {

    @Test
    fun `the earlier verdict and every defect travel with the request`() {
        val text = buildComparisonRequest("Table", VERDICT, ReportLabels.ENGLISH)

        assertTrue(text, text.contains("Compare this one with the Table I checked before"))
        assertTrue(text, text.contains("FAIR"))
        assertTrue(text, text.contains("Solid frame, one joint to sort out"))
        assertTrue(text, text.contains("Gap at the rear leg"))
        assertTrue(text, text.contains("moderate, structural"))
        assertTrue(text, text.contains("not seated the whole way"))
        assertTrue(text, text.contains("Whether the top is sealed"))
        assertTrue(text, text.contains("differences that matter"))
    }

    @Test
    fun `what a defect meant is left out, since only the observation is comparable`() {
        // The assistant can draw the consequence again with both pieces in view. Sending
        // its own earlier interpretation back invites it to repeat itself instead.
        val text = buildComparisonRequest("Table", VERDICT, ReportLabels.ENGLISH)

        assertFalse(text, text.contains("flex every time"))
        assertFalse(text, text.contains("Open it out"))
    }

    @Test
    fun `the request is written in the language of the assessment`() {
        val text = buildComparisonRequest("Meza", VERDICT, ReportLabels.SWAHILI)

        assertTrue(text, text.contains("Linganisha"))
        assertTrue(text, text.contains("WASTANI"))
        assertTrue(text, text.contains("Yaliyoonekana:"))
        assertFalse(text, text.contains("Found on it"))
    }

    @Test
    fun `a verdict with nothing wrong with it still asks the question`() {
        val sound = Verdict(levelId = "sound", headline = "Nothing to fix")
        val text = buildComparisonRequest("Stool or bench", sound, ReportLabels.ENGLISH)

        assertTrue(text, text.contains("SOUND"))
        assertTrue(text, text.contains("differences that matter"))
        assertFalse("no empty findings heading", text.contains("Found on it:"))
    }

    private companion object {
        val VERDICT = Verdict(
            levelId = "fair",
            language = "en",
            headline = "Solid frame, one joint to sort out",
            summary = "Good bones, one joint needs re-gluing.",
            defects = listOf(
                Defect(
                    title = "Gap at the rear leg",
                    area = "structural",
                    severityId = "moderate",
                    whatISee = "The stretcher is not seated the whole way into the leg.",
                    whatItMeans = "That joint will flex every time somebody leans on it.",
                    whatToDo = "Open it out, re-glue and clamp.",
                ),
            ),
            unverified = listOf("Whether the top is sealed. Ask the seller."),
        )
    }
}
