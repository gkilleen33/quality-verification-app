package com.qualityverifier.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the server keeps of a photo's metadata.
 *
 * Everything here is dropped rather than refused: the metadata is optional, and the turn it
 * rides on is not. The one failure that would cost the turn is a value Postgres refuses inside
 * the transaction that stores it, so the cases below include each way to send one.
 */
class PhotoMetaTest {

    private val lenient = Json { isLenient = true }

    private fun full() = buildJsonObject {
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
        put(PhotoMeta.ORIGINAL_WIDTH, 3000)
        put(PhotoMeta.ORIGINAL_HEIGHT, 4000)
        put(PhotoMeta.GPS_LATITUDE, -1.2921)
        put(PhotoMeta.GPS_LONGITUDE, 36.8219)
        put(PhotoMeta.GPS_TIME, "2026-11-10T11:03:20Z")
    }

    @Test
    fun `a phone's metadata is kept as it was sent`() {
        val checked = PhotoMeta.check(full())

        assertEquals(full(), checked.meta)
        assertTrue(checked.dropped.toString(), checked.dropped.isEmpty())
    }

    @Test
    fun `nothing sent is nothing stored`() {
        assertNull(PhotoMeta.check(null).meta)
        assertNull(PhotoMeta.check(kotlinx.serialization.json.JsonNull).meta)
        assertNull(PhotoMeta.check(JsonObject(emptyMap())).meta)
    }

    @Test
    fun `an unknown key is dropped and named, and the rest kept`() {
        val raw = JsonObject(full() + ("maker_note" to JsonPrimitive("opaque")))

        val checked = PhotoMeta.check(raw)

        assertEquals(full(), checked.meta)
        assertEquals(listOf("maker_note"), checked.dropped)
    }

    @Test
    fun `a value of the wrong kind is dropped, not the photo's metadata`() {
        val raw = JsonObject(
            full() + mapOf(
                PhotoMeta.ISO to JsonPrimitive("200"),          // a string where a number goes
                PhotoMeta.MAKE to JsonPrimitive(7),              // a number where a string goes
                PhotoMeta.ORIGINAL_WIDTH to JsonPrimitive(0),    // no photo is 0 px wide
                PhotoMeta.FLASH to JsonPrimitive(1.5),           // a bit field is whole
            )
        )

        val checked = PhotoMeta.check(raw)

        val kept = checked.meta!!
        listOf(PhotoMeta.ISO, PhotoMeta.MAKE, PhotoMeta.ORIGINAL_WIDTH, PhotoMeta.FLASH).forEach {
            assertTrue("$it should have been dropped", it !in kept)
            assertTrue("$it should be named", it in checked.dropped)
        }
        assertEquals(JsonPrimitive("SM-A145F"), kept[PhotoMeta.MODEL])
    }

    @Test
    fun `metadata over the size bound is dropped whole`() {
        val raw = JsonObject(
            full() + (1..200).associate { "padding_$it" to JsonPrimitive("x".repeat(30)) }
        )

        val checked = PhotoMeta.check(raw)

        assertNull(checked.meta)
        assertEquals(1, checked.dropped.size)
    }

    @Test
    fun `something other than an object is dropped`() {
        assertNull(PhotoMeta.check(JsonPrimitive("hello")).meta)
        assertNull(PhotoMeta.check(lenient.parseToJsonElement("[1,2]")).meta)
    }

    @Test
    fun `a string Postgres would refuse is dropped`() {
        // jsonb refuses \u0000, and the insert that stores it is the turn's own transaction.
        val raw = buildJsonObject { put(PhotoMeta.OWNER_NAME, "Ka\u0000mau"); put(PhotoMeta.MAKE, "x") }

        val checked = PhotoMeta.check(raw)

        assertEquals(buildJsonObject { put(PhotoMeta.MAKE, "x") }, checked.meta)
    }

    @Test
    fun `an overlong string is dropped`() {
        val raw = buildJsonObject { put(PhotoMeta.ARTIST, "a".repeat(PhotoMeta.MAX_TEXT_LENGTH + 1)) }

        assertNull(PhotoMeta.check(raw).meta)
    }

    @Test
    fun `a lenient literal comes out as canonical JSON`() {
        // Ktor's reader is lenient. `.5` reads as a number but is not JSON; only the rebuilt
        // value is something Postgres accepts. NaN and an unquoted string go.
        val raw = lenient.parseToJsonElement(
            """{"f_number": .5, "exposure_time_s": NaN, "make": unquoted}"""
        )

        val checked = PhotoMeta.check(raw)

        assertEquals("""{"f_number":0.5}""", checked.meta.toString())
        assertEquals(listOf(PhotoMeta.EXPOSURE_TIME_S, PhotoMeta.MAKE), checked.dropped)
    }

    @Test
    fun `a latitude without its longitude is not kept`() {
        val raw = buildJsonObject { put(PhotoMeta.GPS_LATITUDE, -1.29); put(PhotoMeta.MAKE, "x") }

        val checked = PhotoMeta.check(raw)

        assertEquals(buildJsonObject { put(PhotoMeta.MAKE, "x") }, checked.meta)
        assertEquals(listOf(PhotoMeta.GPS_LATITUDE), checked.dropped)
    }

    @Test
    fun `coordinates off the planet are dropped`() {
        val raw = buildJsonObject { put(PhotoMeta.GPS_LATITUDE, 91.0); put(PhotoMeta.GPS_LONGITUDE, 36.8) }

        assertNull(PhotoMeta.check(raw).meta)
    }

    @Test
    fun `the lens specification is up to four numbers`() {
        val good = lenient.parseToJsonElement("""{"lens_specification":[4.38,4.38,1.8,1.8]}""")
        val bad = lenient.parseToJsonElement("""{"lens_specification":[4.38,"wide"]}""")
        val long = lenient.parseToJsonElement("""{"lens_specification":[1,2,3,4,5]}""")

        assertEquals(good, PhotoMeta.check(good).meta)
        assertNull(PhotoMeta.check(bad).meta)
        assertNull(PhotoMeta.check(long).meta)
    }

    @Test
    fun `without GPS removes exactly the switch's keys`() {
        val stripped = PhotoMeta.withoutGps(full())

        PhotoMeta.GPS.forEach { assertTrue(it, it !in stripped) }
        assertEquals(full().size - 3, stripped.size)   // full() has no altitude
        assertEquals(full()[PhotoMeta.MAKE], stripped[PhotoMeta.MAKE])
    }

    @Test
    fun `every GPS key is a known key`() {
        assertTrue(PhotoMeta.FIELDS.keys.containsAll(PhotoMeta.GPS))
        // The keys are what the stored rows and the data API carry, so the count is pinned:
        // adding one is a change to what is collected, and Nathan decides those.
        assertEquals(25, PhotoMeta.FIELDS.size)
    }
}
