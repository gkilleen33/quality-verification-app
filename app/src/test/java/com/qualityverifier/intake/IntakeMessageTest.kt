package com.qualityverifier.intake

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.buildIntakeMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntakeMessageTest {

    @Test
    fun `a buyer's context reads as something they said`() {
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.ENGLISH,
                ownership = Ownership.BUYING,
                quotedPrice = "3500",
                usage = Usage.DAILY,
            ),
            ReportLabels.ENGLISH,
        )
        assertEquals(
            "I am buying this. The seller is asking 3500. It will get heavy daily use. " +
                "Please answer me in English.",
            text,
        )
    }

    @Test
    fun `an owner is never asked about a price they were not quoted`() {
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.ENGLISH,
                ownership = Ownership.ALREADY_OWN,
                quotedPrice = "ignored",
                usage = Usage.OCCASIONAL,
            ),
            ReportLabels.ENGLISH,
        )
        assertFalse("a price leaked into an owner's context", text.contains("asking"))
        assertTrue(text.startsWith("I already own this one."))
        assertTrue(text.contains("only be used occasionally"))
    }

    @Test
    fun `a skipped price says so rather than going silent`() {
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.ENGLISH,
                ownership = Ownership.BUYING,
                quotedPrice = "   ",
                usage = Usage.BUSINESS,
            ),
            ReportLabels.ENGLISH,
        )
        assertTrue(text.contains("I do not know what price is being asked."))
        assertTrue(text.contains("for a business"))
    }

    @Test
    fun `the opening turn is written in the language that was chosen`() {
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.SWAHILI,
                ownership = Ownership.BUYING,
                quotedPrice = "3500",
                usage = Usage.DAILY,
            ),
            ReportLabels.SWAHILI,
        )
        assertEquals(
            "Ninanunua hii. Muuzaji anataka 3500. Itatumika kwa nguvu kila siku. " +
                "Tafadhali nijibu kwa Kiswahili.",
            text,
        )
    }

    @Test
    fun `every combination states the language, since that is the point`() {
        AssessmentLanguage.entries.forEach { language ->
            val labels = ReportLabels.forLanguage(language.code)
            Ownership.entries.forEach { ownership ->
                Usage.entries.forEach { usage ->
                    val text = buildIntakeMessage(
                        AssessmentContext(language, ownership, "100", usage),
                        labels,
                    )
                    assertTrue(
                        "$language/$ownership/$usage does not name the language",
                        text.contains(labels.intakeSaysUseLanguage),
                    )
                }
            }
        }
    }
}
