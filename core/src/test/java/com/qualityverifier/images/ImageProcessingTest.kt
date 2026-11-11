package com.qualityverifier.images

import androidx.exifinterface.media.ExifInterface
import com.qualityverifier.data.db.ImageFileStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a photo becomes before it is stored, hashed and sent: its size, which way up it
 * ends, and what is left of the camera's metadata. The decode and encode themselves need
 * a device (the emulator check in the PR); these are the decisions around them.
 */
class ImageProcessingTest {

    // ------------------------------------------------------------------ size and rotation

    @Test
    fun `a 12 MP frame arrives with its long edge at the cap`() {
        assertEquals(
            2576 to 1932,
            ImageFileStore.outputSize(4000, 3000, ExifInterface.ORIENTATION_NORMAL),
        )
    }

    // Most portrait photos from a back camera: stored landscape, with orientation 6.
    @Test
    fun `a sideways photo ends upright, capped on its long edge`() {
        assertEquals(
            1932 to 2576,
            ImageFileStore.outputSize(4000, 3000, ExifInterface.ORIENTATION_ROTATE_90),
        )
        assertEquals(
            1932 to 2576,
            ImageFileStore.outputSize(4000, 3000, ExifInterface.ORIENTATION_ROTATE_270),
        )
    }

    // A 50 MP frame decodes at half size, 4080 x 3060, and is scaled from there.
    @Test
    fun `a sub-sampled frame is scaled the rest of the way`() {
        val sample = ImageFileStore.sampleSizeFor(8160, 6120)
        assertEquals(
            2576 to 1932,
            ImageFileStore.outputSize(8160 / sample, 6120 / sample, ExifInterface.ORIENTATION_NORMAL),
        )
    }

    @Test
    fun `a photo under the cap is never enlarged`() {
        assertEquals(1f, ImageFileStore.capScale(1568, 1176), 0f)
        assertEquals(
            1568 to 1176,
            ImageFileStore.outputSize(1568, 1176, ExifInterface.ORIENTATION_NORMAL),
        )
    }

    @Test
    fun `the four sideways orientations swap the sides and the others do not`() {
        val sideways = listOf(
            ExifInterface.ORIENTATION_TRANSPOSE,
            ExifInterface.ORIENTATION_ROTATE_90,
            ExifInterface.ORIENTATION_TRANSVERSE,
            ExifInterface.ORIENTATION_ROTATE_270,
        )
        val level = listOf(
            ExifInterface.ORIENTATION_UNDEFINED,
            ExifInterface.ORIENTATION_NORMAL,
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL,
            ExifInterface.ORIENTATION_ROTATE_180,
            ExifInterface.ORIENTATION_FLIP_VERTICAL,
        )
        sideways.forEach { assertEquals("orientation $it", 1932 to 2576, ImageFileStore.outputSize(4000, 3000, it)) }
        level.forEach { assertEquals("orientation $it", 2576 to 1932, ImageFileStore.outputSize(4000, 3000, it)) }
    }

    // The turn applied, mirror first, then clockwise. 6 (the phone turned a quarter
    // clockwise from landscape) needs a quarter turn clockwise to come back upright.
    @Test
    fun `each orientation maps to the turn that undoes it`() {
        val expected = mapOf(
            ExifInterface.ORIENTATION_NORMAL to (0f to false),
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL to (0f to true),
            ExifInterface.ORIENTATION_ROTATE_180 to (180f to false),
            ExifInterface.ORIENTATION_FLIP_VERTICAL to (180f to true),
            ExifInterface.ORIENTATION_TRANSPOSE to (270f to true),
            ExifInterface.ORIENTATION_ROTATE_90 to (90f to false),
            ExifInterface.ORIENTATION_TRANSVERSE to (90f to true),
            ExifInterface.ORIENTATION_ROTATE_270 to (270f to false),
        )
        expected.forEach { (orientation, turn) ->
            val actual = ImageFileStore.uprightTurn(orientation)
            assertEquals("orientation $orientation", turn, actual.degrees to actual.mirrored)
        }
    }

    // ------------------------------------------------------------------ metadata

    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val length = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (length shr 8).toByte(), length.toByte()) + payload
    }

    private val soi = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val jfif = segment(0xE0, "JFIF\u0000".toByteArray() + ByteArray(9))
    private val exif = segment(0xE1, "Exif\u0000\u0000".toByteArray() + "serial 1234; GPS -1.28,36.81".toByteArray())
    private val xmp = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>".toByteArray())
    private val icc = segment(0xE2, "ICC_PROFILE\u0000".toByteArray() + ByteArray(8))
    private val iptc = segment(0xED, "Photoshop 3.0\u0000".toByteArray() + ByteArray(6))
    private val comment = segment(0xFE, "Owner: Test Owner".toByteArray())
    private val quantisation = segment(0xDB, ByteArray(65) { it.toByte() })
    private val frame = segment(0xC0, ByteArray(15) { 1 })
    private val scan = segment(0xDA, ByteArray(10) { 2 }) +
        byteArrayOf(0x12, 0x34, 0xFF.toByte(), 0x00, 0x56) +
        byteArrayOf(0xFF.toByte(), 0xD9.toByte())

    @Test
    fun `camera metadata is dropped and the image is kept byte for byte`() {
        val camera = soi + jfif + exif + xmp + icc + iptc + comment + quantisation + frame + scan

        val stripped = ImageFileStore.stripMetadata(camera)

        assertArrayEquals(soi + jfif + icc + quantisation + frame + scan, stripped)
        val text = String(stripped, Charsets.ISO_8859_1)
        listOf("Exif", "serial", "xmpmeta", "Photoshop", "Owner").forEach {
            assertTrue("$it should be gone", !text.contains(it))
        }
    }

    // What Bitmap.compress writes: nothing to drop, so the same bytes and the same hash.
    @Test
    fun `an encoder's own output is returned as it is`() {
        val encoded = soi + jfif + quantisation + frame + scan
        assertSame(encoded, ImageFileStore.stripMetadata(encoded))
    }

    @Test
    fun `bytes that are not a whole JPEG are returned as they are`() {
        val notJpeg = "not a photo".toByteArray()
        assertSame(notJpeg, ImageFileStore.stripMetadata(notJpeg))

        // A segment claiming more bytes than the file holds: never guessed at.
        val truncated = soi + exif.copyOf(exif.size - 4)
        assertSame(truncated, ImageFileStore.stripMetadata(truncated))
    }
}
