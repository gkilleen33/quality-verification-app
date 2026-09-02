package com.qualityverifier.server

import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.decoder.Decoder
import com.qualityverifier.server.admin.Qr
import com.qualityverifier.server.admin.Totp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The enrolment QR.
 *
 * These decode the generated code rather than checking it is non-empty, because the failure
 * that matters is not a blank image — it is a code that scans perfectly and yields the
 * wrong secret. That is indistinguishable from a working one until somebody is locked out
 * of the portal, so "it rendered" is not evidence of anything.
 */
class QrTest {

    @Test
    fun `a provisioning URI survives the round trip`() {
        val uri = Totp.provisioningUri("JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP", "someone@example.com")

        assertEquals(uri, decode(Qr.encode(uri)))
    }

    @Test
    fun `a real generated secret survives the round trip`() {
        // Not a fixed vector: exercises whatever alphabet and length newSecret actually
        // produces, so a change to either is caught here.
        val secret = Totp.newSecret()
        val uri = Totp.provisioningUri(secret, "grady@example.com")

        val decoded = decode(Qr.encode(uri))

        assertEquals(uri, decoded)
        assertTrue("the secret must be in the scanned URI", decoded.contains(secret))
    }

    @Test
    fun `an address with characters needing escaping still round trips`() {
        // A '+' in an address is legal and is also how a space encodes. Getting this wrong
        // gives an authenticator the wrong account label, or a broken URI.
        val uri = Totp.provisioningUri(Totp.newSecret(), "grady+kagua@example.com")

        assertEquals(uri, decode(Qr.encode(uri)))
    }

    @Test
    fun `the path only ever uses the characters the view allows through`() {
        // Views.qrCode refuses to emit anything else, so a generator that produced other
        // characters would silently drop the QR rather than render it.
        val allowed = Regex("[Mhvz0-9,\\-]*")
        val path = Qr.encode(Totp.provisioningUri(Totp.newSecret(), "a@b.c")).pathData

        assertTrue("path was: ${path.take(80)}", allowed.matches(path))
        assertTrue("path should not be empty", path.isNotEmpty())
    }

    @Test
    fun `the quiet zone is present on every side`() {
        // Four blank modules. Without them some scanners refuse to read the code at all.
        val qr = Qr.encode(Totp.provisioningUri(Totp.newSecret(), "a@b.c"))
        val filled = parse(qr)

        for (i in 0 until qr.modules) {
            for (edge in 0 until 4) {
                assertTrue("top row $edge is not blank", !filled[edge][i])
                assertTrue("bottom row $edge is not blank", !filled[qr.modules - 1 - edge][i])
                assertTrue("left column $edge is not blank", !filled[i][edge])
                assertTrue("right column $edge is not blank", !filled[i][qr.modules - 1 - edge])
            }
        }
    }

    // ---------------------------------------------------------------- harness

    /** Replays the SVG path back into a grid, so what is decoded is what would be drawn. */
    private fun parse(svg: Qr.Svg): Array<BooleanArray> {
        val grid = Array(svg.modules) { BooleanArray(svg.modules) }
        // Each run is "M<x>,<y>h<n>v1h-<n>z".
        Regex("M(\\d+),(\\d+)h(\\d+)v1h-\\d+z").findAll(svg.pathData).forEach { match ->
            val (x, y, run) = match.destructured
            for (i in 0 until run.toInt()) grid[y.toInt()][x.toInt() + i] = true
        }
        return grid
    }

    private fun decode(svg: Qr.Svg): String {
        val grid = parse(svg)
        // The decoder wants the symbol without its quiet zone.
        val side = svg.modules - 2 * 4
        val matrix = BitMatrix(side, side)
        for (y in 0 until side) {
            for (x in 0 until side) {
                if (grid[y + 4][x + 4]) matrix.set(x, y)
            }
        }
        return Decoder().decode(matrix).text
    }
}
