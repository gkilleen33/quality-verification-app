package com.qualityverifier.ui.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one line of text that sits over the live viewfinder.
 *
 * Worth pinning because of where it is shown rather than what it does. It is drawn on top
 * of the camera preview with nothing else on the screen, so the two failure modes are both
 * silent: markdown left in it puts `**` over somebody's shoulder while they are holding a
 * phone up to a table, and an empty-but-not-null string draws an empty caption bar over
 * the shot. Returning null is how the screen knows to draw nothing at all.
 */
class CaptureInstructionTest {

    @Test
    fun `no assistant text means no instruction`() {
        assertNull(captureInstruction(null))
    }

    @Test
    fun `blank assistant text means no instruction`() {
        assertNull(captureInstruction("   \n  "))
    }

    @Test
    fun `markdown is flattened, not shown`() {
        assertEquals(
            "Fill the frame with the joint.",
            captureInstruction("**Fill the frame** with the `joint`."),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Stand back a metre.", captureInstruction("\n  Stand back a metre.  \n"))
    }

    // A heading and a rule flatten to nothing printable. Without the second takeIf this
    // returned "" and the screen drew a caption with no caption in it.
    @Test
    fun `text that flattens to nothing means no instruction`() {
        assertNull(captureInstruction("---"))
    }
}
