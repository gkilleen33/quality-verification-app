package com.qualityverifier.ui.photo

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Where a photo sits in the viewer: how far it is zoomed, and how far it has been
 * dragged from the centre, in screen pixels.
 *
 * Scale 1 is the photo fitted to the screen. The pan is the translation applied after
 * scaling about the centre, which is how `graphicsLayer` composes the two, so the
 * arithmetic here and the drawing agree without a conversion.
 */
data class PhotoPosition(
    val scale: Float = 1f,
    val panX: Float = 0f,
    val panY: Float = 0f,
) {
    val zoomed: Boolean get() = scale > 1f

    companion object {
        val FITTED = PhotoPosition()
    }
}

/**
 * The geometry of one photo on one screen, and the rules that keep it in view.
 *
 * Plain arithmetic with no Compose types, so the rules can be tested on the JVM: a
 * photo that could be dragged off the screen, or pinched down to a dot, or zoomed
 * short of its own pixels, would each be found only by somebody holding a phone.
 *
 * The photo is drawn fitted inside the viewport (`ContentScale.Fit`), then scaled
 * about the viewport's centre and translated. Every coordinate here is relative to
 * that centre, in screen pixels.
 *
 * @param viewportWidth the box the photo is drawn in, in pixels.
 * @param imageWidth the photo's own size, in pixels, from the decoded file.
 */
class PhotoFrame(
    val viewportWidth: Float,
    val viewportHeight: Float,
    val imageWidth: Float,
    val imageHeight: Float,
) {
    init {
        require(viewportWidth > 0f && viewportHeight > 0f) { "The viewport has no size" }
        require(imageWidth > 0f && imageHeight > 0f) { "The photo has no size" }
    }

    /** The photo at scale 1: as large as fits, keeping its shape. */
    val fittedWidth: Float
    val fittedHeight: Float

    init {
        val fit = min(viewportWidth / imageWidth, viewportHeight / imageHeight)
        fittedWidth = imageWidth * fit
        fittedHeight = imageHeight * fit
    }

    /** The scale at which one pixel of the photo is one pixel of the screen. */
    val pixelScale: Float get() = imageWidth / fittedWidth

    /**
     * How far in the viewer goes. At least [MIN_MAX_SCALE], so a photo already shown
     * near its own size still enlarges; and past the photo's own pixels by
     * [BEYOND_PIXELS], since a gap in a joint is easier to see large and soft than
     * small and sharp.
     */
    val maxScale: Float get() = max(MIN_MAX_SCALE, pixelScale * BEYOND_PIXELS)

    /** Where a double tap takes a fitted photo. Never past [maxScale]. */
    val doubleTapScale: Float get() = min(DOUBLE_TAP_SCALE, maxScale)

    /**
     * The furthest the photo may be dragged along one axis at [scale]. Zero while the
     * scaled photo still fits that axis, so it stays centred rather than sliding about
     * inside the empty space.
     */
    fun maxPanX(scale: Float): Float = max(0f, (fittedWidth * scale - viewportWidth) / 2f)
    fun maxPanY(scale: Float): Float = max(0f, (fittedHeight * scale - viewportHeight) / 2f)

    /** [position] brought back within the limits: never smaller than fitted, never past an edge. */
    fun clamp(position: PhotoPosition): PhotoPosition {
        val scale = position.scale.coerceIn(1f, maxScale)
        return PhotoPosition(
            scale = scale,
            panX = position.panX.coerceIn(-maxPanX(scale), maxPanX(scale)),
            panY = position.panY.coerceIn(-maxPanY(scale), maxPanY(scale)),
        )
    }

    /**
     * A pinch, as the gesture reports it: the fingers' midpoint, how much they moved
     * together, and how much they spread. The point of the photo under the midpoint
     * stays under it, so the joint being pinched does not slide away from the fingers.
     *
     * With the photo at `scale` and `pan`, the screen point `c` shows the photo point
     * `(c - pan) / scale`. Keeping that point under `c` at the new scale gives the new pan.
     */
    fun pinch(
        position: PhotoPosition,
        centroidX: Float,
        centroidY: Float,
        zoomChange: Float,
        panChangeX: Float,
        panChangeY: Float,
    ): PhotoPosition {
        val scale = (position.scale * zoomChange).coerceIn(1f, maxScale)
        val ratio = scale / position.scale
        return clamp(
            PhotoPosition(
                scale = scale,
                panX = centroidX - ratio * (centroidX - position.panX) + panChangeX,
                panY = centroidY - ratio * (centroidY - position.panY) + panChangeY,
            ),
        )
    }

    /**
     * A double tap: a zoomed photo goes back to fitted; a fitted one zooms in to
     * [doubleTapScale] on the tapped point, which is the same rule as [pinch] with
     * the tap as the midpoint.
     */
    fun doubleTap(position: PhotoPosition, x: Float, y: Float): PhotoPosition =
        if (position.zoomed) {
            PhotoPosition.FITTED
        } else {
            pinch(position, x, y, zoomChange = doubleTapScale, panChangeX = 0f, panChangeY = 0f)
        }

    /**
     * Whether a drag that has moved by ([dx], [dy]) belongs to the photo, or to the pager
     * the photo sits in.
     *
     * A fitted photo has nowhere to go, so a drag swipes to the next photo. A zoomed
     * one takes the drag, except a sideways drag at the edge it is already showing:
     * nothing would move, and the maker is more likely reaching for the next photo
     * than pushing against the edge of this one.
     */
    fun takesDrag(position: PhotoPosition, dx: Float, dy: Float): Boolean {
        if (!position.zoomed) return false
        val sideways = abs(dx) > abs(dy)
        if (!sideways) return true
        val limit = maxPanX(position.scale)
        // Dragging the finger left (dx < 0) moves the photo left, towards -limit.
        val atEdge = (dx < 0f && position.panX <= -limit) || (dx > 0f && position.panX >= limit)
        return !atEdge
    }

    companion object {
        /** The least the viewer will enlarge any photo. */
        const val MIN_MAX_SCALE = 4f

        /** How far past the photo's own pixels the viewer goes. */
        const val BEYOND_PIXELS = 2f

        /** Enough to fill the screen with a joint photographed as the plan asks. */
        const val DOUBLE_TAP_SCALE = 3f
    }
}
