package com.qualityverifier.ui.parts

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The logic the shared parts hold (v0.7.1, rows 45, 46, 56, 57). */
class PartsTest {

    @Test
    fun `row 56 - a greyed button says what is missing, and a ready one says nothing`() {
        val greyed = ButtonState("Choose who it's for.")
        assertFalse(greyed.enabled)
        assertEquals("Choose who it's for.", greyed.reason)
        assertTrue(ButtonState.Ready.enabled)
        assertNull(ButtonState.Ready.reason)
    }

    @Test
    fun `row 46 - the Settings menu's state chip reads On in green and Off in grey`() {
        val on = Chip.state(true, "On", "Off")
        val off = Chip.state(false, "On", "Off")
        assertEquals("On", on.label)
        assertEquals("Off", off.label)
        assertNotEquals(on.container, off.container)
        // The mockups' green and grey.
        assertEquals(Chip.linked("x").container, on.container)
        assertEquals(Chip.notLinked("x").container, off.container)
        // The words are the caller's, so Kagua can pass its Kiswahili.
        assertEquals("Imezimwa", Chip.state(false, "Imewashwa", "Imezimwa").label)
    }

    @Test
    fun `a failure box is red and a confirmation green, as the mockups draw them`() {
        assertEquals(0xFFF6D6D2.toInt(), StatusKind.FAILURE.container.toArgb())
        assertEquals(0xFF5B1410.toInt(), StatusKind.FAILURE.content.toArgb())
        assertEquals(0xFFDCEBD3.toInt(), StatusKind.CONFIRMATION.container.toArgb())
    }
}
