package com.qualityverifier.images

import com.qualityverifier.data.db.ImageFileStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decode sampling decides how much memory a camera frame costs before it is scaled.
 * Getting it wrong either OOMs on cheap devices or throws away detail needed to spot
 * a hairline crack.
 */
class ImageSamplingTest {

    @Test
    fun `the cap is the high-resolution tier's long edge`() {
        assertEquals(2576, ImageFileStore.MAX_EDGE_PX)
    }

    @Test
    fun `images at or below the cap are not sub-sampled`() {
        assertEquals(1, ImageFileStore.sampleSizeFor(2576, 1932))
        assertEquals(1, ImageFileStore.sampleSizeFor(1568, 1176))
        assertEquals(1, ImageFileStore.sampleSizeFor(800, 600))
    }

    @Test
    fun `sub-sampling never takes the long edge below the cap`() {
        // A 12 MP frame: 4000 / 2 = 2000, already below 2576, so it decodes whole and is
        // scaled down from there. At the old 1568 cap it was sampled by 2.
        assertEquals(1, ImageFileStore.sampleSizeFor(4000, 3000))
        // 6000 / 2 = 3000, above; / 4 = 1500, below. So 2.
        assertEquals(2, ImageFileStore.sampleSizeFor(6000, 4500))
    }

    @Test
    fun `a large camera frame is sub-sampled aggressively but stays above the cap`() {
        // 50 MP and 108 MP sensors, the largest a phone in the study might write.
        for ((width, height, expected) in listOf(Triple(8160, 6120, 2), Triple(12000, 9000, 4))) {
            val sample = ImageFileStore.sampleSizeFor(width, height)
            assertEquals(expected, sample)
            assertTrue(width / sample >= ImageFileStore.MAX_EDGE_PX)
            assertTrue("and no coarser than it needs", width / (sample * 2) < ImageFileStore.MAX_EDGE_PX)
        }
    }

    @Test
    fun `orientation does not change the result`() {
        assertEquals(
            ImageFileStore.sampleSizeFor(6000, 4500),
            ImageFileStore.sampleSizeFor(4500, 6000),
        )
    }
}
