package com.qualityverifier.location

import com.qualityverifier.data.location.LocationRule
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When an assessment records its point.
 *
 * The case that matters most is the Fundi Bora install from before the question existed:
 * its switch reads on, because that was the default, and the maker never answered. It
 * must record nothing until they do.
 */
class LocationRuleTest {

    @Test
    fun `Fundi Bora records nothing until the maker has answered`() {
        assertFalse(LocationRule.records(switchOn = true, answered = false, answerRequired = true))
    }

    @Test
    fun `a yes records and a no does not`() {
        assertTrue(LocationRule.records(switchOn = true, answered = true, answerRequired = true))
        assertFalse(LocationRule.records(switchOn = false, answered = true, answerRequired = true))
    }

    // Kagua asks on its sign-up form, so its switch alone is the answer, as before.
    @Test
    fun `Kagua's switch counts without the answer flag`() {
        assertTrue(LocationRule.records(switchOn = true, answered = false, answerRequired = false))
        assertFalse(LocationRule.records(switchOn = false, answered = false, answerRequired = false))
    }

    @Test
    fun `a fix starts only with agreement, permission, and location services`() {
        assertTrue(LocationRule.shouldCapture(true, permitted = { true }, providerOn = { true }))
        assertFalse(LocationRule.shouldCapture(false, permitted = { true }, providerOn = { true }))
        // Refused, or "Only this time" since lapsed: read now, so it reads as refused.
        assertFalse(LocationRule.shouldCapture(true, permitted = { false }, providerOn = { true }))
        // The phone's location services are off.
        assertFalse(LocationRule.shouldCapture(true, permitted = { true }, providerOn = { false }))
    }

    // Not agreed means Android is not even asked about the permission.
    @Test
    fun `the permission is not read when the maker has not agreed`() {
        var read = false
        LocationRule.shouldCapture(false, permitted = { read = true; true }, providerOn = { true })
        assertFalse(read)
    }
}
