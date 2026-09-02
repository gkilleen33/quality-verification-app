package com.qualityverifier.server.admin

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * The enrolment QR, as SVG path data.
 *
 * Inline SVG rather than an `<img>`, for two reasons. The portal's CSP allows `img-src
 * 'self'` and would block a `data:` URI, and widening it for one image is a poor trade;
 * serving the QR from its own route instead would mean the TOTP secret existing as a URL,
 * which is the kind of thing that ends up in an access log or a proxy cache. As markup it
 * is neither.
 *
 * ZXing does the encoding. Reed-Solomon and mask selection are not worth hand-rolling: a
 * subtle mistake there yields a code that scans perfectly as the wrong secret, which is
 * indistinguishable from a working one until somebody is locked out.
 */
object Qr {

    /** Quiet zone in modules. Four is the spec's minimum; less and some scanners refuse. */
    private const val QUIET_ZONE = 4

    /**
     * One `<path>` covering every dark module, and the side of the matrix in modules.
     *
     * A single path rather than a rect per module: an otpauth URI is around 45 modules
     * square, so per-module rects would be a couple of thousand elements of markup for a
     * page that has to render on a laptop over a Kampala connection.
     */
    data class Svg(val pathData: String, val modules: Int)

    fun encode(content: String): Svg {
        val matrix = QRCodeWriter().encode(
            content,
            BarcodeFormat.QR_CODE,
            // Size in the writer's own terms; we re-scale with viewBox, so this only needs
            // to be at least the module count.
            1,
            1,
            mapOf(
                // M corrects ~15%. Q or H would survive more damage on a printed code, at
                // the cost of a denser grid — and this is read off a screen once.
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to QUIET_ZONE,
                // The URI is ASCII, so this avoids ZXing guessing a wider charset and
                // emitting an ECI block that some older scanners handle badly.
                EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            ),
        )
        val builder = StringBuilder()
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix.get(x, y)) {
                    x++
                    continue
                }
                // Merge each horizontal run into one rectangle.
                val start = x
                while (x < matrix.width && matrix.get(x, y)) x++
                builder.append("M").append(start).append(",").append(y)
                    .append("h").append(x - start).append("v1h-").append(x - start).append("z")
            }
        }
        return Svg(builder.toString(), matrix.width)
    }
}
