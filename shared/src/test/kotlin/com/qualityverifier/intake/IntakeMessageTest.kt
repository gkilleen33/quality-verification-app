package com.qualityverifier.intake

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.IntakeTopic
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
    fun `row 58 - a question answered in your own words is told in its fixed sentence, in its place`() {
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.ENGLISH,
                ownership = Ownership.BUYING,
                quotedPrice = "",
                usage = null,
                depth = AssessmentDepth.FULL,
                explained = mapOf(
                    IntakeTopic.PRICE to "  He said 9,000 but will take less. ",
                    IntakeTopic.USAGE to "For my mother's shop, two hours a day.",
                ),
            ),
            ReportLabels.ENGLISH,
        )
        assertEquals(
            "I am buying this. About the price: He said 9,000 but will take less. " +
                "About how it will be used: For my mother's shop, two hours a day. " +
                "I would like the full assessment. Please answer me in English.",
            text,
        )
    }

    @Test
    fun `row 58 - the first turn no longer hands questions to the assistant`() {
        // Every question is answered before the start now, so nothing is left for the
        // assistant to ask; a carried intake mentions only what it carries.
        val text = buildIntakeMessage(
            AssessmentContext(
                language = AssessmentLanguage.SWAHILI,
                explained = mapOf(IntakeTopic.OWNERSHIP to "Ni ya dada yangu."),
                usage = Usage.DAILY,
                depth = AssessmentDepth.RAPID,
            ),
            ReportLabels.SWAHILI,
        )
        assertEquals(
            "Kuhusu kununua: Ni ya dada yangu. Itatumika kwa nguvu kila siku. " +
                "Nataka ukaguzi wa haraka. Tafadhali nijibu kwa Kiswahili.",
            text,
        )
        assertFalse(text.contains("niulize wewe mwenyewe"))
    }

    @Test
    fun `row 58 - the same answers always make the same first turn`() {
        // The first turn is in the cached conversation, so it must not vary.
        val context = AssessmentContext(
            language = AssessmentLanguage.ENGLISH,
            ownership = Ownership.ALREADY_OWN,
            usage = Usage.BUSINESS,
            depth = AssessmentDepth.FULL,
            explained = mapOf(IntakeTopic.UPHOLSTERY to "Padded seat, wooden back."),
        )
        assertEquals(buildIntakeMessage(context, ReportLabels.ENGLISH), buildIntakeMessage(context.copy(), ReportLabels.ENGLISH))
        assertTrue(buildIntakeMessage(context, ReportLabels.ENGLISH).startsWith("About the cushioning: Padded seat, wooden back. "))
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
