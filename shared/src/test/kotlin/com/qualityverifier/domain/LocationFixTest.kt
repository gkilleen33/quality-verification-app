package com.qualityverifier.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the handset will and will not send.
 *
 * The bounds exist in three places — here, the route, and a CHECK on the table — and they
 * have to agree. If they drift, the symptom is a constraint violation on a turn somebody
 * spent five minutes on.
 */
class LocationFixTest {

    private fun fix(
        lat: Double = 0.3476,
        lon: Double = 32.5825,
        accuracy: Double = 12.0,
    ) = LocationFix(lat, lon, accuracy, capturedAt = 1_756_000_000_000L)

    @Test
    fun `a normal Kampala fix is usable`() {
        assertTrue(fix().isUsable)
    }

    @Test
    fun `a fix coarser than five kilometres is refused`() {
        // It names a district, not a shop, and this was collected to identify shops.
        assertTrue(fix(accuracy = 4999.0).isUsable)
        assertFalse(fix(accuracy = 5001.0).isUsable)
        // Matches the server's CHECK exactly, boundary included.
        assertTrue(fix(accuracy = LocationFix.MAX_ACCURACY_METRES).isUsable)
    }

    @Test
    fun `an accuracy of zero or less is not an accuracy`() {
        assertFalse(fix(accuracy = 0.0).isUsable)
        assertFalse(fix(accuracy = -1.0).isUsable)
    }

    @Test
    fun `coordinates outside the world are refused`() {
        assertFalse(fix(lat = 91.0).isUsable)
        assertFalse(fix(lat = -91.0).isUsable)
        assertFalse(fix(lon = 181.0).isUsable)
        assertFalse(fix(lon = -181.0).isUsable)
    }

    @Test
    fun `null island is a real place and stays usable`() {
        // 0,0 is in the Atlantic, and it is what a buggy provider returns. This does not
        // pretend to catch that — the point of saying so here is that nothing downstream
        // should treat 0,0 as a synonym for absent. Absent is null.
        assertTrue(fix(lat = 0.0, lon = 0.0).isUsable)
    }
}
