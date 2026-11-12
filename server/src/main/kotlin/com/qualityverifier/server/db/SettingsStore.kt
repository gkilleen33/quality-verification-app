package com.qualityverifier.server.db

import com.qualityverifier.domain.Audience
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("com.qualityverifier.server.settings")

/**
 * The keys of `app_settings` (V27) the server knows how to read.
 *
 * A closed vocabulary, mirrored by the table's `app_settings_key_known` CHECK. A key the
 * table holds and this enum does not is ignored, so a migration can add a key a release
 * before the code that reads it.
 */
enum class SettingKey(val id: String) {
    /** Whether the server refuses an account without a current agreement to the terms. */
    TERMS_GATE("terms_gate"),

    /** The label of the terms text an account must have agreed to. Absent for an app with no terms. */
    TERMS_VERSION("terms_version"),
}

/** The values `terms_gate` may hold, mirrored by `app_settings_value_valid`. */
enum class GateValue(val id: String) {
    ON("on"),
    OFF("off"),
}

/** Reads one app's settings. One indexed query; [AppSettings] decides how often. */
interface SettingsStore {
    /** Every setting stored for [audience], key to value. Empty when there are none. */
    suspend fun settingsFor(audience: Audience): Map<String, String>
}

class PostgresSettingsStore(private val dataSource: DataSource) : SettingsStore {

    // The primary key is (audience, key), so this is one index range scan of at most a
    // handful of rows.
    override suspend fun settingsFor(audience: Audience): Map<String, String> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    "select key, value from app_settings where audience = ?"
                ).use { statement ->
                    statement.setString(1, audience.id)
                    statement.executeQuery().use { rows ->
                        val out = mutableMapOf<String, String>()
                        while (rows.next()) out[rows.getString(1)] = rows.getString(2)
                        out
                    }
                }
            }
        }
}

/**
 * Each app's settings, as the routes read them, at most [maxAge] old.
 *
 * Cached because the terms gate runs on every authenticated request, and a settings query
 * per request would double the queries of the cheapest routes for a value that changes a
 * few times a year. Ten seconds is the delay between Nathan changing a setting and every
 * phone feeling it, which is short enough that nobody needs to restart anything.
 *
 * The cache is in this process. One server runs today; a second would need a shared cache
 * or a shorter age, since each would see a change at its own time.
 *
 * **Every unreadable answer reads as off.** A missing row, a value this code does not
 * recognise, and a database that cannot be reached all leave the gate open, the same rule
 * AppVersions follows for the minimum version: a typo in a setting must never lock every
 * phone out. The CHECKs in V27 make a typo unlikely; this makes it harmless.
 */
class AppSettings(
    private val store: SettingsStore,
    private val now: () -> Instant = Instant::now,
    private val maxAge: Duration = MAX_AGE,
) {
    private class Entry(val values: Map<String, String>, val readAt: Instant)

    private val cache = ConcurrentHashMap<Audience, Entry>()

    /** Whether the terms gate is on for [audience]. Anything but `on` is off. */
    suspend fun termsGateOn(audience: Audience): Boolean =
        valuesFor(audience)[SettingKey.TERMS_GATE.id] == GateValue.ON.id

    /**
     * The terms version [audience] must have agreed to, or null when the app has no terms.
     * Kagua has no row, and so no terms.
     */
    suspend fun termsVersion(audience: Audience): String? =
        valuesFor(audience)[SettingKey.TERMS_VERSION.id]?.trim()?.takeIf { it.isNotEmpty() }

    /** Forgets [audience]'s cached answer, so the next read goes to the database. */
    fun clear(audience: Audience) {
        cache.remove(audience)
    }

    private suspend fun valuesFor(audience: Audience): Map<String, String> {
        val at = now()
        val cached = cache[audience]
        if (cached != null && Duration.between(cached.readAt, at) < maxAge) return cached.values
        val fresh = try {
            store.settingsFor(audience)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Not cached, so the next request tries the database again. The last answer
            // read stands in until then; with none, every setting reads as its default.
            log.warn("Could not read settings for {}; using the last known values", audience.id, e)
            return cached?.values ?: emptyMap()
        }
        cache[audience] = Entry(fresh, at)
        return fresh
    }

    companion object {
        /** The longest a change waits before the server sees it. */
        val MAX_AGE: Duration = Duration.ofSeconds(10)

        /** No database: every setting at its default, so the gate is off. For tests and a server without one. */
        val UNCONFIGURED = AppSettings(
            object : SettingsStore {
                override suspend fun settingsFor(audience: Audience): Map<String, String> = emptyMap()
            },
        )
    }
}
