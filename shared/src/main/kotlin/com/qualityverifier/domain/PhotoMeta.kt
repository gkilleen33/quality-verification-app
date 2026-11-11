package com.qualityverifier.domain

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The camera metadata kept with a photo (v0.4.0, D3), as one JSON object per photo.
 *
 * The phone re-encodes every photo before it is hashed and uploaded, and the re-encoded file
 * carries no EXIF: a serial number or a GPS fix left inside the JPEG would reach Claude with
 * the pixels and would bypass the location switch. So the fields worth keeping are read from
 * the original bytes first and travel beside the photo, in the turn's `photo_meta`, and the
 * server stores them in `attachments.photo_meta` (V25). They never enter a request to Claude.
 *
 * Here rather than in either app or the server so the key names cannot drift: the phone
 * writes these keys and the server keeps only these keys. Every key is optional. A phone
 * leaves most of them empty, especially for a photo taken inside the app, and a key the phone
 * could not read is absent rather than null or zero.
 *
 * A closed vocabulary of keys, but not mirrored by a database CHECK: a key the server does
 * not know is dropped by [check] before the row is written, and a new key is then a server
 * release rather than a migration.
 */
object PhotoMeta {

    /** What a key's value must be. */
    enum class Kind {
        /** A string of 1 to [MAX_TEXT_LENGTH] characters, with no NUL (jsonb refuses one). */
        TEXT,

        /** A whole number. */
        INTEGER,

        /** A finite number. */
        NUMBER,

        /** One to four finite numbers. Only the lens specification. */
        NUMBERS,
    }

    // When the photo was taken.

    /** EXIF DateTimeOriginal as local time, `yyyy-MM-ddTHH:mm:ss`. The camera's clock. */
    const val TAKEN_AT = "taken_at"

    /** EXIF OffsetTimeOriginal, `+03:00`: the offset of [TAKEN_AT] from UTC, if recorded. */
    const val TAKEN_AT_OFFSET = "taken_at_offset"

    // The camera and its settings.

    const val MAKE = "make"
    const val MODEL = "model"

    /** Seconds. */
    const val EXPOSURE_TIME_S = "exposure_time_s"

    /** The aperture as an F-number: 1.8 for f/1.8. */
    const val F_NUMBER = "f_number"

    /** EXIF PhotographicSensitivity (ISOSpeedRatings). */
    const val ISO = "iso"

    /** EXIF Flash, the raw bit field: bit 0 says whether it fired. 0 = did not fire. */
    const val FLASH = "flash"

    /** The lens's real focal length, millimetres. */
    const val FOCAL_LENGTH_MM = "focal_length_mm"

    /** The same in 35 mm film terms, which is comparable across phones. */
    const val FOCAL_LENGTH_35MM = "focal_length_35mm"

    /** EXIF WhiteBalance: 0 automatic, 1 manual. */
    const val WHITE_BALANCE = "white_balance"

    const val LENS_MAKE = "lens_make"
    const val LENS_MODEL = "lens_model"

    /**
     * EXIF LensSpecification: shortest and longest focal length (mm), then the smallest
     * F-number at each. Four numbers when the camera wrote all four.
     */
    const val LENS_SPECIFICATION = "lens_specification"

    // Identifiers. Personal: they can tie photos to one handset or one person.

    const val BODY_SERIAL = "body_serial"
    const val LENS_SERIAL = "lens_serial"
    const val IMAGE_UNIQUE_ID = "image_unique_id"

    /** EXIF CameraOwnerName. */
    const val OWNER_NAME = "owner_name"
    const val ARTIST = "artist"

    // The photo before it was shrunk, turned upright: comparable with the uploaded image.

    const val ORIGINAL_WIDTH = "original_width"
    const val ORIGINAL_HEIGHT = "original_height"

    // Where the camera says it was. Only while the app's location switch is on.

    const val GPS_LATITUDE = "gps_latitude"
    const val GPS_LONGITUDE = "gps_longitude"

    /** Metres above sea level; negative below. */
    const val GPS_ALTITUDE_M = "gps_altitude_m"

    /** EXIF GPSDateStamp and GPSTimeStamp together, UTC: `yyyy-MM-ddTHH:mm:ssZ`. */
    const val GPS_TIME = "gps_time"

    /** Every key, in the order the phone writes them. Anything else is dropped. */
    val FIELDS: Map<String, Kind> = linkedMapOf(
        TAKEN_AT to Kind.TEXT,
        TAKEN_AT_OFFSET to Kind.TEXT,
        MAKE to Kind.TEXT,
        MODEL to Kind.TEXT,
        EXPOSURE_TIME_S to Kind.NUMBER,
        F_NUMBER to Kind.NUMBER,
        ISO to Kind.INTEGER,
        FLASH to Kind.INTEGER,
        FOCAL_LENGTH_MM to Kind.NUMBER,
        FOCAL_LENGTH_35MM to Kind.INTEGER,
        WHITE_BALANCE to Kind.INTEGER,
        LENS_MAKE to Kind.TEXT,
        LENS_MODEL to Kind.TEXT,
        LENS_SPECIFICATION to Kind.NUMBERS,
        BODY_SERIAL to Kind.TEXT,
        LENS_SERIAL to Kind.TEXT,
        IMAGE_UNIQUE_ID to Kind.TEXT,
        OWNER_NAME to Kind.TEXT,
        ARTIST to Kind.TEXT,
        ORIGINAL_WIDTH to Kind.INTEGER,
        ORIGINAL_HEIGHT to Kind.INTEGER,
        GPS_LATITUDE to Kind.NUMBER,
        GPS_LONGITUDE to Kind.NUMBER,
        GPS_ALTITUDE_M to Kind.NUMBER,
        GPS_TIME to Kind.TEXT,
    )

    /** The keys the location switch governs. */
    val GPS: Set<String> = setOf(GPS_LATITUDE, GPS_LONGITUDE, GPS_ALTITUDE_M, GPS_TIME)

    /**
     * The most one photo's metadata may take, as UTF-8 JSON text. Every field filled to
     * its longest in ASCII is under 2 KB; a phone fills a few hundred bytes. Anything
     * bigger is not what the app sends, and is dropped whole.
     */
    const val MAX_BYTES = 4096

    /** Longest string kept. EXIF strings this long are padding or junk. */
    const val MAX_TEXT_LENGTH = 64

    /** What [check] kept, and the keys it dropped. Never the dropped values: they may be personal. */
    data class Checked(val meta: JsonObject?, val dropped: List<String>)

    /**
     * [raw] reduced to known keys with values of the right kind, rebuilt so the stored JSON
     * is canonical whatever the sender's formatting. Null when nothing is left.
     *
     * Never throws. The metadata is optional research data on a turn somebody spent
     * minutes on, so a bad value costs that value, a bad object costs that photo's
     * metadata, and neither costs the turn.
     */
    fun check(raw: JsonElement?): Checked {
        if (raw == null || raw is JsonNull) return Checked(null, emptyList())
        if (raw !is JsonObject) return Checked(null, listOf("(not an object)"))
        if (raw.toString().toByteArray(Charsets.UTF_8).size > MAX_BYTES) {
            return Checked(null, listOf("(over $MAX_BYTES bytes)"))
        }
        val kept = linkedMapOf<String, JsonElement>()
        val dropped = mutableListOf<String>()
        for ((key, value) in raw) {
            val kind = FIELDS[key]
            val clean = kind?.let { cleaned(key, it, value) }
            if (clean == null) dropped += key else kept[key] = clean
        }
        // A latitude without its longitude is not a place.
        if ((GPS_LATITUDE in kept) != (GPS_LONGITUDE in kept)) {
            listOf(GPS_LATITUDE, GPS_LONGITUDE).filter { it in kept }.forEach {
                kept.remove(it)
                dropped += it
            }
        }
        return Checked(if (kept.isEmpty()) null else JsonObject(kept), dropped)
    }

    /** [meta] without the keys the location switch governs. */
    fun withoutGps(meta: JsonObject): JsonObject = JsonObject(meta.filterKeys { it !in GPS })

    // Every kept value is a new primitive built from the parsed value, never the one that
    // arrived: Ktor's reader is lenient, and a literal such as `.5` parses as a number here
    // but is not JSON, so passed through it would make Postgres refuse the whole turn.
    private fun cleaned(key: String, kind: Kind, value: JsonElement): JsonElement? {
        return when (kind) {
            Kind.TEXT -> (value as? JsonPrimitive)
                ?.takeIf { it.isString }
                ?.content
                ?.takeIf { it.isNotEmpty() && it.length <= MAX_TEXT_LENGTH && '\u0000' !in it }
                ?.let { JsonPrimitive(it) }
            Kind.INTEGER -> (value as? JsonPrimitive)
                ?.takeIf { !it.isString }
                ?.longOrNull
                ?.takeIf { inRange(key, it.toDouble()) }
                ?.let { JsonPrimitive(it) }
            Kind.NUMBER -> number(value)?.takeIf { inRange(key, it) }?.let { JsonPrimitive(it) }
            Kind.NUMBERS -> {
                val array = (value as? JsonArray)?.takeIf { it.size in 1..4 } ?: return null
                val numbers = array.map { number(it) ?: return null }
                JsonArray(numbers.map { JsonPrimitive(it) })
            }
        }
    }

    private fun number(value: JsonElement): Double? = (value as? JsonPrimitive)
        ?.takeIf { !it.isString }
        ?.doubleOrNull
        ?.takeIf { it.isFinite() }

    /** Bounds where a value outside them can only be a fault. */
    private fun inRange(key: String, value: Double): Boolean = when (key) {
        GPS_LATITUDE -> value in -90.0..90.0
        GPS_LONGITUDE -> value in -180.0..180.0
        ORIGINAL_WIDTH, ORIGINAL_HEIGHT, ISO, FOCAL_LENGTH_35MM -> value > 0
        FLASH, WHITE_BALANCE -> value >= 0
        else -> true
    }
}
