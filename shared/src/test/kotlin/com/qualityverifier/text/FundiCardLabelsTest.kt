package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The words on Fundi Bora's reply cards. The card's size and layout are Compose and are in
 * the PR's emulator screenshots; what a unit test can hold is the wording.
 */
class FundiCardLabelsTest {

    @Test
    fun `row 41 - the diagnosis heading reads Diagnosis, in sentence case`() {
        assertEquals("Diagnosis", FundiLabels.ENGLISH.diagnosisHeading)
    }

    @Test
    fun `row 41 - no label carries the old subhead`() {
        // The field is gone, so the compiler holds the card to it; this holds every other
        // label to it too, in case the words were moved rather than removed.
        assertFalse(FundiLabels.ENGLISH.toString().contains("Not a grade"))
    }
}
