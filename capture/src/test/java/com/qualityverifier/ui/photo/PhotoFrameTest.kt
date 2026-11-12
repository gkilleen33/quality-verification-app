package com.qualityverifier.ui.photo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The limits the photo viewer keeps.
 *
 * Each of these is a thing a maker would notice only by doing it on a phone: a photo
 * dragged until it is off the screen with no way back, a pinch that shrinks it to
 * nothing, a double tap that zooms in but never out. The numbers are a 2,576 x 1,932
 * photo, which is the largest the app stores, on a 1,080 x 2,200 portrait screen.
 */
class PhotoFrameTest {

    private val frame = PhotoFrame(
        viewportWidth = 1080f,
        viewportHeight = 2200f,
        imageWidth = 2576f,
        imageHeight = 1932f,
    )

    @Test
    fun `a landscape photo on a portrait screen fits its width`() {
        assertEquals(1080f, frame.fittedWidth, 0.01f)
        assertEquals(810f, frame.fittedHeight, 0.01f)
    }

    @Test
    fun `the viewer zooms at least to the photo's own pixels`() {
        // 2,576 photo pixels across 1,080 screen pixels: 1:1 at about 2.4x.
        assertEquals(2576f / 1080f, frame.pixelScale, 0.001f)
        assertTrue(frame.maxScale >= frame.pixelScale)
    }

    @Test
    fun `a small photo still enlarges`() {
        // Already shown at its own size; the floor is what makes it zoomable at all.
        val small = PhotoFrame(1080f, 2200f, imageWidth = 540f, imageHeight = 400f)
        assertEquals(PhotoFrame.MIN_MAX_SCALE, small.maxScale, 0.001f)
    }

    @Test
    fun `zoom is clamped at both ends`() {
        val far = frame.pinch(PhotoPosition.FITTED, 0f, 0f, zoomChange = 100f, panChangeX = 0f, panChangeY = 0f)
        assertEquals(frame.maxScale, far.scale, 0.001f)

        val shrunk = frame.pinch(far, 0f, 0f, zoomChange = 0.001f, panChangeX = 0f, panChangeY = 0f)
        assertEquals(PhotoPosition.FITTED, shrunk)
    }

    @Test
    fun `the photo cannot be dragged off the screen`() {
        val zoomed = frame.pinch(PhotoPosition.FITTED, 0f, 0f, zoomChange = 2f, panChangeX = 0f, panChangeY = 0f)
        val dragged = frame.pinch(zoomed, 0f, 0f, zoomChange = 1f, panChangeX = -10_000f, panChangeY = 10_000f)

        // Sideways: the photo is 2,160 wide on a 1,080 screen, so 540 each way and no more.
        assertEquals(-540f, dragged.panX, 0.01f)
        // Up and down: 1,620 tall on a 2,200 screen, so it stays centred.
        assertEquals(0f, dragged.panY, 0.01f)
    }

    @Test
    fun `a pinch keeps the point under the fingers where it is`() {
        // Pinch at 200 px right of centre, from fitted to 2x. The photo point that was
        // under the fingers must still be under them. On the centre line vertically,
        // since at 2x the photo still fits the screen's height and stays centred.
        val before = PhotoPosition.FITTED
        val after = frame.pinch(before, 200f, 0f, zoomChange = 2f, panChangeX = 0f, panChangeY = 0f)
        assertEquals(2f, after.scale, 0.001f)
        assertEquals(photoPointUnder(before, 200f, 0f), photoPointUnder(after, 200f, 0f))
    }

    @Test
    fun `a double tap zooms in on the tap, and a second one zooms out`() {
        val zoomed = frame.doubleTap(PhotoPosition.FITTED, 100f, 0f)
        assertEquals(frame.doubleTapScale, zoomed.scale, 0.001f)
        assertEquals(photoPointUnder(PhotoPosition.FITTED, 100f, 0f), photoPointUnder(zoomed, 100f, 0f))

        assertEquals(PhotoPosition.FITTED, frame.doubleTap(zoomed, 100f, 0f))
    }

    @Test
    fun `a double tap on a photo zoomed by hand zooms out`() {
        // Any zoom at all, not only the double tap's own: the tap is the way back.
        val pinched = frame.pinch(PhotoPosition.FITTED, 0f, 0f, zoomChange = 1.3f, panChangeX = 0f, panChangeY = 0f)
        assertEquals(PhotoPosition.FITTED, frame.doubleTap(pinched, 0f, 0f))
    }

    @Test
    fun `a fitted photo leaves every drag to the pager`() {
        assertFalse(frame.takesDrag(PhotoPosition.FITTED, dx = -50f, dy = 0f))
        assertFalse(frame.takesDrag(PhotoPosition.FITTED, dx = 0f, dy = 50f))
    }

    @Test
    fun `a zoomed photo takes the drag until it reaches its edge`() {
        val zoomed = frame.pinch(PhotoPosition.FITTED, 0f, 0f, zoomChange = 2f, panChangeX = 0f, panChangeY = 0f)
        assertTrue(frame.takesDrag(zoomed, dx = -50f, dy = 0f))

        // Pushed all the way left, showing its right edge: a further drag left goes to
        // the pager; a drag right, or mostly downward, still moves the photo.
        val atEdge = frame.pinch(zoomed, 0f, 0f, zoomChange = 1f, panChangeX = -10_000f, panChangeY = 0f)
        assertFalse(frame.takesDrag(atEdge, dx = -50f, dy = 5f))
        assertTrue(frame.takesDrag(atEdge, dx = 50f, dy = 0f))
        assertTrue(frame.takesDrag(atEdge, dx = -5f, dy = 50f))
    }

    /** The photo point, in fitted-photo pixels from its centre, shown at screen point ([x], [y]). */
    private fun photoPointUnder(position: PhotoPosition, x: Float, y: Float): Pair<Int, Int> =
        Math.round((x - position.panX) / position.scale) to Math.round((y - position.panY) / position.scale)
}
