package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The location switch in Fundi Bora's Settings reads as the question the maker answered.
 *
 * A maker who agreed at setup should find the same words when they go looking for the
 * setting later, not Kagua's sentence about shops and the start of evaluations.
 */
class FundiSettingsLabelsTest {

    private val fundi = FundiLabels.ENGLISH
    private val settings = fundi.onSettings(AuthLabels.ENGLISH)

    @Test
    fun `the switch and its hint are Fundi Bora's, not Kagua's`() {
        assertEquals(fundi.assessLocationSwitch, settings.recordLocationLabel)
        assertEquals(fundi.assessLocationHint, settings.recordLocationHint)
    }

    // Everything the two apps word the same stays Kagua's.
    @Test
    fun `the rest of the screen is unchanged`() {
        assertEquals(AuthLabels.ENGLISH.locationHeading, settings.locationHeading)
        assertEquals(AuthLabels.ENGLISH.allowLocationAccess, settings.allowLocationAccess)
    }
}
