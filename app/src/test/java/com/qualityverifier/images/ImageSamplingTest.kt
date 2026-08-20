package com.qualityverifier.images

import com.qualityverifier.data.db.ImageFileStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Decode sampling decides how much memory a camera frame costs before it is scaled.
 * Getting it wrong either OOMs on cheap devices or throws away detail needed to spot
 * a hairline crack.
 */
class ImageSamplingTest {

    @Test
    fun `images at or below the cap are not sub-sampled`() {
        assertEquals(1, ImageFileStore.sampleSizeFor(1568, 1000))
        assertEquals(1, ImageFileStore.sampleSizeFor(800, 600))
    }

    @Test
    fun `sub-sampling never takes the long edge below the cap`() {
        // 4000 / 2 = 2000, still above 1568; /4 = 1000, below. So 2 is correct.
        assertEquals(2, ImageFileStore.sampleSizeFor(4000, 3000))
    }

    @Test
    fun `a large camera frame is sub-sampled aggressively but stays above the cap`() {
        val sample = ImageFileStore.sampleSizeFor(8000, 6000)
        assertEquals(4, sample)
        assert(8000 / sample >= ImageFileStore.MAX_EDGE_PX)
    }

    @Test
    fun `orientation does not change the result`() {
        assertEquals(
            ImageFileStore.sampleSizeFor(4000, 3000),
            ImageFileStore.sampleSizeFor(3000, 4000),
        )
    }
}
