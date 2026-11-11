package com.qualityverifier.images

import androidx.exifinterface.media.ExifInterface
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.domain.PhotoMeta
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which EXIF tags become which metadata keys, and what is left out.
 *
 * Against a fake EXIF source: ExifInterface needs real JPEG bytes and a device, and the
 * parser is not what is being tested. The emulator check in the PR covers a real photo.
 */
class PhotoMetaReaderTest {

    /** EXIF as ExifInterface reports it: strings, with rationals as `a/b`. */
    private class FakeExif(
        private val tags: Map<String, String>,
        private val latLong: DoubleArray? = null,
        private val altitude: Double? = null,
    ) : ExifSource {
        /** Every tag the reader asked for, so a test can say what was never read. */
        val asked = mutableSetOf<String>()

        override fun text(tag: String): String? = tags[tag].also { asked += tag }
        override fun number(tag: String): Double? = text(tag)?.let { value ->
            if ('/' in value) {
                val (n, d) = value.split('/').map { it.toDouble() }
                n / d
            } else {
                value.toDoubleOrNull()
            }
        }
        override fun integer(tag: String): Int? = text(tag)?.toIntOrNull()
        override fun latLong(): DoubleArray? = latLong.also { asked += "latLong" }
        override fun altitude(): Double? = altitude.also { asked += "altitude" }
    }

    /** A phone photo with most fields filled, as a Samsung writes them. */
    private fun samsung() = FakeExif(
        tags = mapOf(
            ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:10 14:03:22",
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL to "+03:00",
            ExifInterface.TAG_MAKE to "samsung",
            ExifInterface.TAG_MODEL to "SM-A145F",
            ExifInterface.TAG_EXPOSURE_TIME to "0.01",
            ExifInterface.TAG_F_NUMBER to "1.8",
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "200",
            ExifInterface.TAG_FLASH to "0",
            ExifInterface.TAG_FOCAL_LENGTH to "438/100",
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM to "26",
            ExifInterface.TAG_WHITE_BALANCE to "0",
            ExifInterface.TAG_LENS_MAKE to "samsung",
            ExifInterface.TAG_LENS_MODEL to "Samsung Galaxy A14 back camera 4.38mm f/1.8",
            ExifInterface.TAG_LENS_SPECIFICATION to "438/100,438/100,18/10,18/10",
            ExifInterface.TAG_BODY_SERIAL_NUMBER to "R58T90ABCDE",
            ExifInterface.TAG_LENS_SERIAL_NUMBER to "L123",
            ExifInterface.TAG_IMAGE_UNIQUE_ID to "XA0LLMF00SM",
            ExifInterface.TAG_CAMERA_OWNER_NAME to "Kamau",
            ExifInterface.TAG_ARTIST to "Kamau Furniture",
            ExifInterface.TAG_GPS_DATESTAMP to "2026:11:10",
            ExifInterface.TAG_GPS_TIMESTAMP to "11:03:20",
            // Never read: the maker's private block and the embedded thumbnail.
            ExifInterface.TAG_MAKER_NOTE to "SECRET-MAKER-NOTE",
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT to "1234",
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH to "5678",
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH to "512",
        ),
        latLong = doubleArrayOf(-1.2921, 36.8219),
        altitude = 1661.0,
    )

    @Test
    fun `every field maps to its key`() {
        val meta = PhotoMetaReader.read(samsung(), width = 3000, height = 4000, includeGps = true)

        val expected = buildJsonObject {
            put(PhotoMeta.TAKEN_AT, "2026-11-10T14:03:22")
            put(PhotoMeta.TAKEN_AT_OFFSET, "+03:00")
            put(PhotoMeta.MAKE, "samsung")
            put(PhotoMeta.MODEL, "SM-A145F")
            put(PhotoMeta.EXPOSURE_TIME_S, 0.01)
            put(PhotoMeta.F_NUMBER, 1.8)
            put(PhotoMeta.ISO, 200)
            put(PhotoMeta.FLASH, 0)
            put(PhotoMeta.FOCAL_LENGTH_MM, 4.38)
            put(PhotoMeta.FOCAL_LENGTH_35MM, 26)
            put(PhotoMeta.WHITE_BALANCE, 0)
            put(PhotoMeta.LENS_MAKE, "samsung")
            put(PhotoMeta.LENS_MODEL, "Samsung Galaxy A14 back camera 4.38mm f/1.8")
            put(
                PhotoMeta.LENS_SPECIFICATION,
                JsonArray(listOf(4.38, 4.38, 1.8, 1.8).map { JsonPrimitive(it) }),
            )
            put(PhotoMeta.BODY_SERIAL, "R58T90ABCDE")
            put(PhotoMeta.LENS_SERIAL, "L123")
            put(PhotoMeta.IMAGE_UNIQUE_ID, "XA0LLMF00SM")
            put(PhotoMeta.OWNER_NAME, "Kamau")
            put(PhotoMeta.ARTIST, "Kamau Furniture")
            put(PhotoMeta.ORIGINAL_WIDTH, 3000)
            put(PhotoMeta.ORIGINAL_HEIGHT, 4000)
            put(PhotoMeta.GPS_LATITUDE, -1.2921)
            put(PhotoMeta.GPS_LONGITUDE, 36.8219)
            put(PhotoMeta.GPS_ALTITUDE_M, 1661.0)
            put(PhotoMeta.GPS_TIME, "2026-11-10T11:03:20Z")
        }
        assertEquals(expected, meta)
        // Every key is one the server keeps, so nothing the phone reads is dropped there.
        assertEquals(meta, PhotoMeta.check(meta).meta)
    }

    @Test
    fun `GPS is neither read nor kept while the location switch is off`() {
        val exif = samsung()

        val meta = PhotoMetaReader.read(exif, width = 3000, height = 4000, includeGps = false)

        PhotoMeta.GPS.forEach { assertTrue("$it kept with the switch off", it !in meta) }
        assertTrue(exif.asked.toString(), "latLong" !in exif.asked && "altitude" !in exif.asked)
        assertTrue(ExifInterface.TAG_GPS_DATESTAMP !in exif.asked)
        // The rest is unaffected.
        assertEquals(JsonPrimitive("R58T90ABCDE"), meta[PhotoMeta.BODY_SERIAL])
    }

    @Test
    fun `MakerNote and the thumbnail are never read or kept`() {
        val exif = samsung()

        val meta = PhotoMetaReader.read(exif, width = 3000, height = 4000, includeGps = true)

        listOf(
            ExifInterface.TAG_MAKER_NOTE,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT,
            ExifInterface.TAG_JPEG_INTERCHANGE_FORMAT_LENGTH,
            ExifInterface.TAG_THUMBNAIL_IMAGE_WIDTH,
        ).forEach { assertTrue("$it was read", it !in exif.asked) }
        assertTrue(meta.toString(), !meta.toString().contains("SECRET-MAKER-NOTE"))
        assertTrue(meta.keys.all { it in PhotoMeta.FIELDS })
    }

    @Test
    fun `fields the phone left empty are omitted, not sent as null or zero`() {
        // A photo taken inside the app on a budget phone: a date, a make, and padding.
        val exif = FakeExif(
            tags = mapOf(
                ExifInterface.TAG_DATETIME_ORIGINAL to "2026:11:10 14:03:22",
                ExifInterface.TAG_MAKE to "TECNO\u0000\u0000\u0000",
                ExifInterface.TAG_MODEL to "   ",
                ExifInterface.TAG_ARTIST to "",
                ExifInterface.TAG_BODY_SERIAL_NUMBER to "\u0000\u0000\u0000\u0000",
                ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY to "0",
                ExifInterface.TAG_EXPOSURE_TIME to "0/0",
                ExifInterface.TAG_LENS_SPECIFICATION to "438/100,438/100,0/0,0/0",
            ),
        )

        val meta = PhotoMetaReader.read(exif, width = 720, height = 1280, includeGps = true)

        assertEquals(
            buildJsonObject {
                put(PhotoMeta.TAKEN_AT, "2026-11-10T14:03:22")
                put(PhotoMeta.MAKE, "TECNO")
                put(PhotoMeta.ORIGINAL_WIDTH, 720)
                put(PhotoMeta.ORIGINAL_HEIGHT, 1280)
            },
            meta,
        )
    }

    @Test
    fun `a photo with no readable EXIF keeps only its size`() {
        val meta = PhotoMetaReader.read(null, width = 1600, height = 1200, includeGps = true)

        assertEquals(
            buildJsonObject {
                put(PhotoMeta.ORIGINAL_WIDTH, 1600)
                put(PhotoMeta.ORIGINAL_HEIGHT, 1200)
            },
            meta,
        )
    }

    @Test
    fun `a long string is cut to what the server keeps`() {
        val exif = FakeExif(mapOf(ExifInterface.TAG_ARTIST to "a".repeat(200)))

        val meta = PhotoMetaReader.read(exif, width = 10, height = 10, includeGps = false)

        assertEquals(JsonPrimitive("a".repeat(PhotoMeta.MAX_TEXT_LENGTH)), meta[PhotoMeta.ARTIST])
    }

    @Test
    fun `a camera with no clock writes zeros, and those are no date`() {
        assertNull(PhotoMetaReader.localDateTime("0000:00:00 00:00:00"))
        assertNull(PhotoMetaReader.localDateTime("    :  :     :  :  "))
        assertNull(PhotoMetaReader.gpsTime("0000:00:00", "00:00:00"))
        assertEquals("2026-11-10T14:03:22", PhotoMetaReader.localDateTime("2026:11:10 14:03:22"))
    }

    @Test
    fun `an offset must look like one`() {
        assertEquals("-05:00", PhotoMetaReader.offset("-05:00"))
        assertNull(PhotoMetaReader.offset("EAT"))
    }

    // Most portrait photos from a back camera are stored landscape, with orientation 6.
    @Test
    fun `the original size is given upright, so it compares with the photo as sent`() {
        assertEquals(3000 to 4000, ImageFileStore.uprightSize(4000, 3000, ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(4000 to 3000, ImageFileStore.uprightSize(4000, 3000, ExifInterface.ORIENTATION_NORMAL))
    }
}
