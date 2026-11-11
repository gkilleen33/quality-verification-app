package com.qualityverifier.images

import androidx.exifinterface.media.ExifInterface
import com.qualityverifier.domain.PhotoMeta
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The EXIF values [PhotoMetaReader] asks for, one tag at a time.
 *
 * An interface so the mapping can be tested without a device: [ExifInterface] parses real
 * JPEG bytes, and what is worth testing is which tags are read and how they are written,
 * not the parser. Each method answers null when the tag is absent or unreadable.
 */
interface ExifSource {
    fun text(tag: String): String?

    /** A rational or decimal tag as a number. */
    fun number(tag: String): Double?

    fun integer(tag: String): Int?

    /** Latitude and longitude in decimal degrees, signed, or null without both. */
    fun latLong(): DoubleArray?

    /** Metres above sea level, negative below, or null when the camera wrote none. */
    fun altitude(): Double?
}

/** [ExifSource] over the photo's own EXIF, read from the original bytes. */
class ExifInterfaceSource(private val exif: ExifInterface) : ExifSource {
    override fun text(tag: String): String? = exif.getAttribute(tag)

    override fun number(tag: String): Double? =
        exif.getAttributeDouble(tag, Double.NaN).takeIf { !it.isNaN() }

    override fun integer(tag: String): Int? =
        exif.getAttributeInt(tag, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    override fun latLong(): DoubleArray? = exif.latLong

    override fun altitude(): Double? =
        if (exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null) exif.getAltitude(0.0) else null
}

/**
 * Reads the camera metadata kept with a photo (v0.4.0, D3) from its original EXIF, before
 * the photo is re-encoded without it. The keys and their meaning are [PhotoMeta]'s.
 *
 * Reads only the tags listed below. MakerNote, the manufacturer's undocumented private
 * block, and the embedded thumbnail are never asked for, so nothing in them can be kept.
 * A tag the camera left empty, or wrote as padding, is left out rather than sent as null
 * or zero: many phones fill few of these, especially for a photo taken inside the app.
 */
object PhotoMetaReader {

    /**
     * @param exif the original photo's EXIF, or null when it had none that could be read.
     * @param width the original's width once turned upright, before it was shrunk.
     * @param height likewise.
     * @param includeGps whether the app's location switch is on. Off, the camera's GPS is
     *   not read at all: the switch is the person's answer, and the camera's own setting
     *   is one the app cannot change.
     */
    fun read(exif: ExifSource?, width: Int, height: Int, includeGps: Boolean): JsonObject {
        val out = linkedMapOf<String, JsonElement>()
        fun text(key: String, value: String?) {
            clean(value)?.let { out[key] = JsonPrimitive(it) }
        }
        fun number(key: String, value: Double?, valid: (Double) -> Boolean = { it > 0 }) {
            value?.takeIf { it.isFinite() && valid(it) }?.let { out[key] = JsonPrimitive(it) }
        }
        fun integer(key: String, value: Int?, valid: (Int) -> Boolean = { it > 0 }) {
            value?.takeIf(valid)?.let { out[key] = JsonPrimitive(it) }
        }

        if (exif != null) {
            text(PhotoMeta.TAKEN_AT, localDateTime(exif.text(ExifInterface.TAG_DATETIME_ORIGINAL)))
            text(PhotoMeta.TAKEN_AT_OFFSET, offset(exif.text(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)))
            text(PhotoMeta.MAKE, exif.text(ExifInterface.TAG_MAKE))
            text(PhotoMeta.MODEL, exif.text(ExifInterface.TAG_MODEL))
            number(PhotoMeta.EXPOSURE_TIME_S, exif.number(ExifInterface.TAG_EXPOSURE_TIME))
            number(PhotoMeta.F_NUMBER, exif.number(ExifInterface.TAG_F_NUMBER))
            integer(PhotoMeta.ISO, exif.integer(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY))
            // Zero is an answer for these two: "did not fire", "automatic".
            integer(PhotoMeta.FLASH, exif.integer(ExifInterface.TAG_FLASH)) { it >= 0 }
            number(PhotoMeta.FOCAL_LENGTH_MM, exif.number(ExifInterface.TAG_FOCAL_LENGTH))
            integer(PhotoMeta.FOCAL_LENGTH_35MM, exif.integer(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM))
            integer(PhotoMeta.WHITE_BALANCE, exif.integer(ExifInterface.TAG_WHITE_BALANCE)) { it >= 0 }
            text(PhotoMeta.LENS_MAKE, exif.text(ExifInterface.TAG_LENS_MAKE))
            text(PhotoMeta.LENS_MODEL, exif.text(ExifInterface.TAG_LENS_MODEL))
            lensSpecification(exif.text(ExifInterface.TAG_LENS_SPECIFICATION))?.let {
                out[PhotoMeta.LENS_SPECIFICATION] = JsonArray(it.map { n -> JsonPrimitive(n) })
            }
            text(PhotoMeta.BODY_SERIAL, exif.text(ExifInterface.TAG_BODY_SERIAL_NUMBER))
            text(PhotoMeta.LENS_SERIAL, exif.text(ExifInterface.TAG_LENS_SERIAL_NUMBER))
            text(PhotoMeta.IMAGE_UNIQUE_ID, exif.text(ExifInterface.TAG_IMAGE_UNIQUE_ID))
            text(PhotoMeta.OWNER_NAME, exif.text(ExifInterface.TAG_CAMERA_OWNER_NAME))
            text(PhotoMeta.ARTIST, exif.text(ExifInterface.TAG_ARTIST))
        }
        integer(PhotoMeta.ORIGINAL_WIDTH, width)
        integer(PhotoMeta.ORIGINAL_HEIGHT, height)

        if (exif != null && includeGps) {
            exif.latLong()
                ?.takeIf { it.size == 2 && it[0] in -90.0..90.0 && it[1] in -180.0..180.0 }
                ?.let { (lat, lon) ->
                    out[PhotoMeta.GPS_LATITUDE] = JsonPrimitive(lat)
                    out[PhotoMeta.GPS_LONGITUDE] = JsonPrimitive(lon)
                }
            number(PhotoMeta.GPS_ALTITUDE_M, exif.altitude()) { true }
            text(
                PhotoMeta.GPS_TIME,
                gpsTime(exif.text(ExifInterface.TAG_GPS_DATESTAMP), exif.text(ExifInterface.TAG_GPS_TIMESTAMP)),
            )
        }
        return JsonObject(out)
    }

    /**
     * EXIF strings are fixed-length ASCII, padded with NULs or spaces by many cameras. Both
     * go, and a value longer than the server keeps is cut to fit rather than lost.
     */
    private fun clean(value: String?): String? =
        value?.replace("\u0000", "")?.trim()?.take(PhotoMeta.MAX_TEXT_LENGTH)?.trim()
            ?.takeIf { it.isNotEmpty() }

    private val EXIF_DATE_TIME = Regex("""^(\d{4}):(\d{2}):(\d{2}) (\d{2}):(\d{2}):(\d{2})""")
    private val EXIF_DATE = Regex("""^(\d{4}):(\d{2}):(\d{2})$""")
    private val EXIF_TIME = Regex("""^(\d{2}):(\d{2}):(\d{2})$""")
    private val OFFSET = Regex("""^[+-]\d{2}:\d{2}$""")

    /** `2026:11:10 14:03:22` as `2026-11-10T14:03:22`. A camera with no clock writes zeros. */
    internal fun localDateTime(value: String?): String? {
        val (y, mo, d, h, mi, s) = EXIF_DATE_TIME.find(value?.trim() ?: return null)?.destructured
            ?: return null
        if (y == "0000" || mo == "00" || d == "00") return null
        return "$y-$mo-${d}T$h:$mi:$s"
    }

    internal fun offset(value: String?): String? = value?.trim()?.takeIf { OFFSET.matches(it) }

    /** GPS date and time, both UTC, as one instant: `2026-11-10T11:03:20Z`. */
    internal fun gpsTime(date: String?, time: String?): String? {
        val (y, mo, d) = EXIF_DATE.find(date?.trim() ?: return null)?.destructured ?: return null
        val (h, mi, s) = EXIF_TIME.find(time?.trim() ?: return null)?.destructured ?: return null
        if (y == "0000" || mo == "00" || d == "00") return null
        return "$y-$mo-${d}T$h:$mi:${s}Z"
    }

    /**
     * LensSpecification as [ExifInterface] gives it, four rationals, `"438/100,438/100,18/10,18/10"`.
     * A camera that does not know one of them writes 0/0; then the four lose their positions,
     * so the whole tag is left out rather than sent shorter.
     */
    internal fun lensSpecification(value: String?): List<Double>? {
        val parts = value?.split(',')?.map { it.trim() } ?: return null
        if (parts.size != 4) return null
        return parts.map { part ->
            val number = if ('/' in part) {
                val (num, den) = part.split('/', limit = 2).map { it.trim().toDoubleOrNull() ?: return null }
                if (den == 0.0) return null else num / den
            } else {
                part.toDoubleOrNull() ?: return null
            }
            if (!number.isFinite() || number < 0) return null
            number
        }
    }
}
