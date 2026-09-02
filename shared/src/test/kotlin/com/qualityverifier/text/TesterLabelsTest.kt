package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The evaluator questionnaire's wording.
 *
 * These are a research instrument rather than decoration: a scale whose direction is lost
 * in translation produces numbers that look valid and mean the opposite.
 */
class TesterLabelsTest {

    @Test
    fun `the assessment's language wins, and the device is only a fallback`() {
        assertSame(TesterLabels.SWAHILI, TesterLabels.forLanguage("sw", "en"))
        assertSame(TesterLabels.ENGLISH, TesterLabels.forLanguage("en", "sw"))
        assertSame(TesterLabels.SWAHILI, TesterLabels.forLanguage(null, "sw-KE"))
        assertSame(TesterLabels.ENGLISH, TesterLabels.forLanguage(null, null))
    }

    @Test
    fun `an unrecognised language falls back rather than guessing`() {
        assertSame(TesterLabels.ENGLISH, TesterLabels.forLanguage("fr"))
        assertSame(TesterLabels.ENGLISH, TesterLabels.forLanguage(""))
    }

    @Test
    fun `every string is translated, and none left as its English twin`() {
        val en = TesterLabels.ENGLISH
        val sw = TesterLabels.SWAHILI
        listOf(
            en.title to sw.title,
            en.blurb to sw.blurb,
            en.mistakesQuestion to sw.mistakesQuestion,
            en.mistakesYes to sw.mistakesYes,
            en.mistakesNo to sw.mistakesNo,
            en.mistakesUnsure to sw.mistakesUnsure,
            en.mistakesDetailLabel to sw.mistakesDetailLabel,
            en.mistakesDetailHint to sw.mistakesDetailHint,
            en.adviceQuestion to sw.adviceQuestion,
            en.adviceLow to sw.adviceLow,
            en.adviceHigh to sw.adviceHigh,
            en.itemQuestion to sw.itemQuestion,
            en.itemLow to sw.itemLow,
            en.itemHigh to sw.itemHigh,
            en.extraLabel to sw.extraLabel,
            en.extraHint to sw.extraHint,
            en.submit to sw.submit,
            en.later to sw.later,
            en.prompt to sw.prompt,
            en.promptAction to sw.promptAction,
            en.thanks to sw.thanks,
            en.saved to sw.saved,
        ).forEach { (english, swahili) ->
            assertTrue("a label is empty", english.isNotBlank() && swahili.isNotBlank())
            assertTrue("\"$english\" was not translated", english != swahili)
        }
    }

    @Test
    fun `both scales keep their endpoints, in both languages`() {
        // The direction of each scale lives in these strings. Lose it and a 1 and a 10 are
        // recorded the wrong way round, which no later analysis can detect.
        listOf(TesterLabels.ENGLISH, TesterLabels.SWAHILI).forEach { labels ->
            assertTrue("${labels.code}: item scale must state 1", labels.itemLow.contains("1"))
            assertTrue("${labels.code}: item scale must state 10", labels.itemHigh.contains("10"))
            assertTrue("${labels.code}: needs a low anchor", labels.adviceLow.isNotBlank())
            assertTrue("${labels.code}: needs a high anchor", labels.adviceHigh.isNotBlank())
            // And the two ends must not read the same.
            assertTrue("${labels.code}", labels.adviceLow != labels.adviceHigh)
            assertTrue("${labels.code}", labels.itemLow != labels.itemHigh)
        }
    }

    @Test
    fun `the mistake options are three distinct answers`() {
        listOf(TesterLabels.ENGLISH, TesterLabels.SWAHILI).forEach { labels ->
            assertEquals(
                "${labels.code}: yes, no and not-sure must differ",
                3,
                setOf(labels.mistakesYes, labels.mistakesNo, labels.mistakesUnsure).size,
            )
        }
    }
}
