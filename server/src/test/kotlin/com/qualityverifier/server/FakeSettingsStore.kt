package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.db.SettingsStore
import java.time.Duration
import java.time.Instant

/**
 * An in-memory `app_settings`, keyed as the table is, by app and key.
 *
 * Holds raw strings rather than typed values, so a test can store what the CHECKs in V27
 * would refuse and prove the reader still reads it as off.
 */
class FakeSettingsStore(initial: Map<Pair<Audience, String>, String> = emptyMap()) : SettingsStore {
    val values = initial.toMutableMap()

    /** How many times the reader went to the "database". */
    var reads = 0
        private set

    /** Set to make the next reads fail, as an unreachable database would. */
    var failure: Exception? = null

    fun set(audience: Audience, key: String, value: String) {
        values[audience to key] = value
    }

    override suspend fun settingsFor(audience: Audience): Map<String, String> {
        reads++
        failure?.let { throw it }
        return values.filterKeys { it.first == audience }.mapKeys { it.key.second }
    }
}

/** A clock a test moves by hand. */
class TestClock(var now: Instant = Instant.parse("2026-11-11T09:00:00Z")) {
    fun advance(by: Duration) {
        now = now.plus(by)
    }
}
