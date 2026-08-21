package com.qualityverifier.intake

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
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
                depth = AssessmentDepth.FULL,
            ),
            ReportLabels.ENGLISH,
        )
        assertEquals(
            "I am buying this. The seller is asking 3500. It will get heavy daily use. " +
                "I would like the full assessment. Please answer me in English.",
            text,
        )
        // Nothing is missing, so the assistant is not asked to take over.
        assertFalse(text.contains("ask me yourself"))
    }

    @Test
    fun `an owner is never asked about a price they were not quoted`() {
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.ENGLISH,
                ownership = Ownership.ALREADY_OWN,
                quotedPrice = "ignored",
                usage = Usage.OCCASIONAL,
                depth = AssessmentDepth.RAPID,
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
                depth = AssessmentDepth.FULL,
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
                depth = AssessmentDepth.FULL,
            ),
            ReportLabels.SWAHILI,
        )
        assertEquals(
            "Ninanunua hii. Muuzaji anataka 3500. Itatumika kwa nguvu kila siku. " +
                "Nataka ukaguzi kamili. Tafadhali nijibu kwa Kiswahili.",
            text,
        )
    }

    @Test
    fun `abandoning the intake hands over what was already chosen`() {
        // The customer got as far as saying they are buying, then could not find their
        // answer among the buttons. Making them repeat the part they managed would be the
        // second time the app failed them.
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.ENGLISH,
                ownership = Ownership.BUYING,
                quotedPrice = "3500",
                usage = null,
                depth = null,
            ),
            ReportLabels.ENGLISH,
        )
        assertTrue(text.contains("I am buying this."))
        assertTrue(text.contains("The seller is asking 3500."))
        // Nothing invented for the answers that were never given.
        assertFalse(text.contains("daily"))
        assertFalse(text.contains("assessment."))
        // And the assistant is told to pick the questioning up itself.
        assertTrue(text.endsWith("so please ask me yourself."))
    }

    @Test
    fun `abandoning at the very first question still asks for help`() {
        val text = buildIntakeMessage(
            AssessmentContext(language = AssessmentLanguage.SWAHILI),
            ReportLabels.SWAHILI,
        )
        assertEquals(
            "Tafadhali nijibu kwa Kiswahili. " +
                "Sikuweza kujibu mengine kwa vitufe, tafadhali niulize wewe mwenyewe.",
            text,
        )
    }

    @Test
    fun `a context missing only the depth is still a handover`() {
        // isComplete is what decides, so a single unanswered question is enough. Sending
        // it as though it were complete would leave the assistant expecting a depth it
        // was never given and issuing the wrong plan.
        val context = AssessmentContext(
            language = AssessmentLanguage.ENGLISH,
            ownership = Ownership.ALREADY_OWN,
            usage = Usage.DAILY,
            depth = null,
        )
        assertFalse(context.isComplete)
        assertTrue(buildIntakeMessage(context, ReportLabels.ENGLISH).contains("ask me yourself"))
    }

    @Test
    fun `every combination states the language, since that is the point`() {
        AssessmentLanguage.entries.forEach { language ->
            val labels = ReportLabels.forLanguage(language.code)
            Ownership.entries.forEach { ownership ->
                Usage.entries.forEach { usage ->
                    val text = buildIntakeMessage(
                        AssessmentContext(
                            language, ownership, "100", usage, AssessmentDepth.FULL,
                        ),
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
