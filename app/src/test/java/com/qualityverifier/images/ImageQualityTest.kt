package com.qualityverifier.images

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageQualityTest {

    @Test
    fun `a flat grey field is neither dark nor sharp`() {
        val quality = measureQuality(IntArray(64 * 64) { 128 }, 64, 64)
        assertEquals(0.0, quality.sharpness, 1e-9)
        assertEquals(128.0, quality.brightness, 1e-9)
        assertTrue(quality.tooBlurry)
        assertFalse(quality.tooDark)
    }

    @Test
    fun `a hard checkerboard is measured as sharp`() {
        val size = 64
        val pixels = IntArray(size * size) { i ->
            if (((i / size) + (i % size)) % 2 == 0) 0 else 255
        }
        val quality = measureQuality(pixels, size, size)
        assertFalse(quality.tooBlurry)
        assertNull(quality.warning)
    }

    @Test
    fun `a soft gradient reads as blurred`() {
        val size = 64
        val pixels = IntArray(size * size) { i -> (i % size) * 4 }
        assertTrue(measureQuality(pixels, size, size).tooBlurry)
    }

    @Test
    fun `a dark frame is reported as dark`() {
        val quality = measureQuality(IntArray(32 * 32) { 12 }, 32, 32)
        assertTrue(quality.tooDark)
        assertEquals(
            "This one is dark and blurred. Turn the flash on, hold still, and try again.",
            quality.warning,
        )
    }

    @Test
    fun `a dark but sharp frame is told to use the flash`() {
        val size = 32
        val pixels = IntArray(size * size) { i ->
            if (((i / size) + (i % size)) % 2 == 0) 0 else 60
        }
        val quality = measureQuality(pixels, size, size)
        assertTrue(quality.tooDark)
        assertFalse(quality.tooBlurry)
        assertEquals("This one is quite dark. Turn the flash on and try again.", quality.warning)
    }

    @Test
    fun `a failed decode degrades to no measurement rather than a crash`() {
        val quality = measureQuality(IntArray(0), 0, 0)
        assertEquals(0.0, quality.brightness, 1e-9)
        // Zeroes mean "we learned nothing", and the caller must not treat that as a
        // verdict on the photo — it says both, which the UI shows as advice, not a block.
        assertNotNull(quality.warning)
    }

    @Test
    fun `luma follows perceived brightness rather than raw channels`() {
        val green = lumaOf(intArrayOf(0xFF00FF00.toInt()))[0]
        val blue = lumaOf(intArrayOf(0xFF0000FF.toInt()))[0]
        val white = lumaOf(intArrayOf(0xFFFFFFFF.toInt()))[0]
        assertTrue("green should read brighter than blue", green > blue)
        assertEquals(255, white)
        assertEquals(0, lumaOf(intArrayOf(0xFF000000.toInt()))[0])
    }
}
