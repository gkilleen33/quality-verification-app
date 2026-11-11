package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateLabelsTest {

    @Test
    fun `the phone's language decides, with English as the fallback`() {
        assertSame(UpdateLabels.SWAHILI, UpdateLabels.forDevice("sw-KE"))
        assertSame(UpdateLabels.ENGLISH, UpdateLabels.forDevice("en"))
        assertSame(UpdateLabels.ENGLISH, UpdateLabels.forDevice("fr"))
        assertSame(UpdateLabels.ENGLISH, UpdateLabels.forDevice(null))
    }

    @Test
    fun `the decided wording is used as decided`() {
        assertEquals("A new version is available", UpdateLabels.ENGLISH.newVersionAvailable)
        assertEquals("Download", UpdateLabels.ENGLISH.download)
    }

    @Test
    fun `every string is translated, and none is left as its English twin`() {
        val en = UpdateLabels.ENGLISH
        val sw = UpdateLabels.SWAHILI
        listOf(
            en.newVersionAvailable to sw.newVersionAvailable,
            en.download to sw.download,
            en.dismiss to sw.dismiss,
            en.updateRequiredTitle to sw.updateRequiredTitle,
            en.updateRequiredBody to sw.updateRequiredBody,
        ).forEach { (english, swahili) ->
            assertTrue("a label is empty", english.isNotBlank() && swahili.isNotBlank())
            assertTrue("\"$english\" was not translated", english != swahili)
        }
    }
}
