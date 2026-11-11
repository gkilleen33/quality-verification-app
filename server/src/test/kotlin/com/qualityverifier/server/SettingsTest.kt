package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.db.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/**
 * Reading `app_settings` (V27): the cache, and the rule that anything unreadable is off.
 *
 * The rule matters more than the cache. The terms gate refuses phones when it reads as on,
 * so the failure to guard against is a setting that reads as on when nobody meant it: a
 * value the code does not recognise, a missing row, or a database that cannot answer.
 */
class SettingsTest {

    private val clock = TestClock()

    private fun settings(store: FakeSettingsStore) = AppSettings(store, now = { clock.now })

    private fun seeded() = FakeSettingsStore(
        mapOf(
            (Audience.FUNDI to "terms_gate") to "off",
            (Audience.BUYER to "terms_gate") to "off",
            (Audience.FUNDI to "terms_version") to "2026-11",
        )
    )

    @Test
    fun `the seeded values read as V27 writes them`() = runBlocking<Unit> {
        val settings = settings(seeded())

        assertFalse(settings.termsGateOn(Audience.FUNDI))
        assertFalse(settings.termsGateOn(Audience.BUYER))
        assertEquals("2026-11", settings.termsVersion(Audience.FUNDI))
        // Kagua has no terms_version row, which is how it has no terms.
        assertNull(settings.termsVersion(Audience.BUYER))
    }

    @Test
    fun `a change is not seen within ten seconds of the last read`() = runBlocking<Unit> {
        val store = seeded()
        val settings = settings(store)
        assertFalse(settings.termsGateOn(Audience.FUNDI))

        store.set(Audience.FUNDI, "terms_gate", "on")
        clock.advance(Duration.ofMillis(9_999))

        assertFalse("still the cached answer", settings.termsGateOn(Audience.FUNDI))
        assertEquals("one query, not two", 1, store.reads)
    }

    @Test
    fun `a change is seen once ten seconds have passed`() = runBlocking<Unit> {
        val store = seeded()
        val settings = settings(store)
        assertFalse(settings.termsGateOn(Audience.FUNDI))

        store.set(Audience.FUNDI, "terms_gate", "on")
        clock.advance(Duration.ofSeconds(10))

        assertTrue(settings.termsGateOn(Audience.FUNDI))
        assertEquals(2, store.reads)
    }

    @Test
    fun `each app is cached on its own`() = runBlocking<Unit> {
        val store = seeded()
        val settings = settings(store)
        settings.termsGateOn(Audience.FUNDI)
        settings.termsGateOn(Audience.BUYER)
        settings.termsVersion(Audience.FUNDI)

        assertEquals("one query per app, and the second key came from the cache", 2, store.reads)
    }

    @Test
    fun `clear forces a fresh read for that app only`() = runBlocking<Unit> {
        val store = seeded()
        val settings = settings(store)
        settings.termsGateOn(Audience.FUNDI)
        settings.termsGateOn(Audience.BUYER)

        store.set(Audience.FUNDI, "terms_gate", "on")
        store.set(Audience.BUYER, "terms_gate", "on")
        settings.clear(Audience.FUNDI)

        assertTrue(settings.termsGateOn(Audience.FUNDI))
        assertFalse("Kagua's answer is still cached", settings.termsGateOn(Audience.BUYER))
    }

    @Test
    fun `a missing gate row reads as off`() = runBlocking<Unit> {
        val settings = settings(FakeSettingsStore())

        assertFalse(settings.termsGateOn(Audience.FUNDI))
        assertNull(settings.termsVersion(Audience.FUNDI))
    }

    @Test
    fun `an unreadable gate value reads as off`() = runBlocking<Unit> {
        // V27's CHECK refuses each of these. The reader must not depend on it.
        listOf("ON", " on", "yes", "true", "1", "").forEach { value ->
            val store = FakeSettingsStore(mapOf((Audience.FUNDI to "terms_gate") to value))
            assertFalse("'$value' must read as off", settings(store).termsGateOn(Audience.FUNDI))
        }
    }

    @Test
    fun `a blank terms version reads as no terms`() = runBlocking<Unit> {
        val store = FakeSettingsStore(mapOf((Audience.FUNDI to "terms_version") to "  "))
        assertNull(settings(store).termsVersion(Audience.FUNDI))
    }

    @Test
    fun `a key the code does not know is ignored`() = runBlocking<Unit> {
        // A later migration may add a key a release before the code that reads it.
        val store = seeded().apply { set(Audience.FUNDI, "approval_required", "on") }
        assertFalse(settings(store).termsGateOn(Audience.FUNDI))
    }

    @Test
    fun `an unreachable database reads as off when nothing was read before`() = runBlocking<Unit> {
        val store = seeded().apply {
            set(Audience.FUNDI, "terms_gate", "on")
            failure = IllegalStateException("connection refused")
        }
        assertFalse(settings(store).termsGateOn(Audience.FUNDI))
    }

    @Test
    fun `an unreachable database keeps the last answer and is asked again next time`() = runBlocking<Unit> {
        val store = seeded().apply { set(Audience.FUNDI, "terms_gate", "on") }
        val settings = settings(store)
        assertTrue(settings.termsGateOn(Audience.FUNDI))

        store.failure = IllegalStateException("connection refused")
        clock.advance(Duration.ofSeconds(11))
        assertTrue("the last answer stands in", settings.termsGateOn(Audience.FUNDI))

        // A failure is not cached: the very next read tries again, without waiting ten seconds.
        store.failure = null
        store.set(Audience.FUNDI, "terms_gate", "off")
        assertFalse(settings.termsGateOn(Audience.FUNDI))
        assertEquals(3, store.reads)
    }

    @Test
    fun `with no database every setting is off`() = runBlocking<Unit> {
        assertFalse(AppSettings.UNCONFIGURED.termsGateOn(Audience.FUNDI))
        assertFalse(AppSettings.UNCONFIGURED.termsGateOn(Audience.BUYER))
        assertNull(AppSettings.UNCONFIGURED.termsVersion(Audience.FUNDI))
    }
}
