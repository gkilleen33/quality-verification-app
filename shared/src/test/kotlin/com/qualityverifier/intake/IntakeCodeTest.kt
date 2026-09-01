package com.qualityverifier.intake

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.decodeIntake
import com.qualityverifier.text.encodeIntake
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class IntakeCodeTest {

    @Test
    fun `a complete intake survives the round trip`() {
        val decoded = decodeIntake(encodeIntake(COMPLETE))
        assertEquals(AssessmentLanguage.SWAHILI, decoded?.language)
        assertEquals(Ownership.ALREADY_OWN, decoded?.ownership)
        assertEquals(Usage.BUSINESS, decoded?.usage)
        assertEquals(AssessmentDepth.RAPID, decoded?.depth)
    }

    @Test
    fun `the price is not carried to the next piece`() {
        // The one answer that is about this piece rather than about the afternoon. A
        // price silently inherited from the last stool is a wrong number in front of
        // the assistant.
        val decoded = decodeIntake(encodeIntake(COMPLETE.copy(quotedPrice = "3500")))
        assertEquals("", decoded?.quotedPrice)
    }

    @Test
    fun `an intake that was handed over encodes to nothing`() {
        // Nothing whole to carry, so the next piece gets the full intake.
        assertNull(encodeIntake(AssessmentContext(language = AssessmentLanguage.ENGLISH)))
        assertNull(encodeIntake(COMPLETE.copy(usage = null)))
    }

    @Test
    fun `anything unrecognised decodes to nothing rather than to a guess`() {
        // A route from an older build must fall back to asking properly. Half a context
        // would answer questions nobody answered.
        listOf(
            null,
            "",
            "sw",
            "sw-buying-daily",
            "sw-buying-daily-full-extra",
            "xx-buying-daily-full",
            "sw-renting-daily-full",
            "sw-buying-hourly-full",
            "sw-buying-daily-thorough",
        ).forEach { assertNull("decoded \"$it\"", decodeIntake(it)) }
    }

    @Test
    fun `case and surrounding space do not matter`() {
        assertNotNull(decodeIntake("  SW-ALREADY_OWN-BUSINESS-RAPID  "))
    }

    private companion object {
        val COMPLETE = AssessmentContext(
            language = AssessmentLanguage.SWAHILI,
            ownership = Ownership.ALREADY_OWN,
            usage = Usage.BUSINESS,
            depth = AssessmentDepth.RAPID,
        )
    }
}
