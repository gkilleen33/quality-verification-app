package com.qualityverifier.images

/**
 * What a quick look at a photo can tell us before spending a request on it.
 *
 * Two failures are worth catching on the device: a photo too blurred to read a joint
 * line in, and a photo too dark to see anything in — both routine when the subject is
 * under a workbench in a shed. Catching them here saves the customer a round trip on
 * paid data and saves the assessment from being built on a picture nobody can read.
 *
 * Advisory only. The thresholds below are conservative on purpose, and a warning always
 * offers "use it anyway": a false positive that blocks a usable photo is a worse bug
 * than a false negative that costs one request.
 */
data class ImageQuality(
    /** Variance of the Laplacian. Higher is sharper. */
    val sharpness: Double,
    /** Mean luma, 0..255. */
    val brightness: Double,
) {
    val tooBlurry: Boolean get() = sharpness < BLUR_LIMIT
    val tooDark: Boolean get() = brightness < DARK_LIMIT

    /** A sentence to show the user, or null when the photo is worth sending. */
    val warning: String?
        get() = when {
            // Dark first: darkness is usually what caused the blur, and the fix for it
            // (turn the flash on) is the more useful thing to say.
            tooDark && tooBlurry ->
                "This one is dark and blurred. Turn the flash on, hold still, and try again."
            tooDark -> "This one is quite dark. Turn the flash on and try again."
            tooBlurry -> "This one looks blurred. Hold still for a moment and try again."
            else -> null
        }

    private companion object {
        /**
         * Both thresholds are engineering judgement rather than measurement — they have
         * not been calibrated against real photos from real sheds yet, which is why they
         * sit low enough to fire only on the obvious cases.
         */
        const val BLUR_LIMIT = 40.0
        const val DARK_LIMIT = 45.0
    }
}

/**
 * Measures [luma] (row-major, 0..255) laid out as [width] by [height].
 *
 * Sharpness is the variance of a 4-neighbour Laplacian over the interior pixels: an
 * in-focus edge produces a large second derivative, a blurred one does not. Returns
 * zeroes for an image too small to have an interior, which is what a failed decode
 * looks like.
 */
fun measureQuality(luma: IntArray, width: Int, height: Int): ImageQuality {
    if (width < 3 || height < 3 || luma.size < width * height) {
        return ImageQuality(sharpness = 0.0, brightness = 0.0)
    }

    var lumaSum = 0.0
    for (value in luma) lumaSum += value
    val brightness = lumaSum / luma.size

    var sum = 0.0
    var sumSquares = 0.0
    var count = 0
    for (y in 1 until height - 1) {
        val row = y * width
        for (x in 1 until width - 1) {
            val i = row + x
            val laplacian = 4.0 * luma[i] -
                luma[i - 1] - luma[i + 1] - luma[i - width] - luma[i + width]
            sum += laplacian
            sumSquares += laplacian * laplacian
            count++
        }
    }
    val mean = sum / count
    val variance = (sumSquares / count) - (mean * mean)
    return ImageQuality(sharpness = maxOf(0.0, variance), brightness = brightness)
}

/** Rec. 601 luma from packed ARGB, which is what Bitmap.getPixels hands back. */
fun lumaOf(pixels: IntArray): IntArray = IntArray(pixels.size) { index ->
    val pixel = pixels[index]
    val r = (pixel shr 16) and 0xFF
    val g = (pixel shr 8) and 0xFF
    val b = pixel and 0xFF
    ((299 * r + 587 * g + 114 * b) / 1000).coerceIn(0, 255)
}
