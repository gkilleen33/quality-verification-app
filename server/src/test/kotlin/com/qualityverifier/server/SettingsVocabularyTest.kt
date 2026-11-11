package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.db.GateValue
import com.qualityverifier.server.db.SettingKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The settings vocabulary in Kotlin and in V27's CHECKs has to be the same list.
 *
 * Read from the migration because the server tests have no database, as FundiVocabularyTest
 * does. Unlike that test, this one checks both directions for keys and gate values. A key
 * the CHECK allows and the code does not read is a setting Nathan can change to no effect;
 * a key the code reads and the CHECK refuses can never be set. Either way the gate would
 * not do what the settings table says.
 *
 * When a later migration replaces these CHECKs, point [migration] at it.
 */
class SettingsVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V27__app_settings.sql")
        assertTrue(
            "expected the migration at ${file.absolutePath}; the module root moved?",
            file.exists(),
        )
        // Comments out, so a word in the header cannot satisfy a CHECK.
        file.readText().lines().joinToString("\n") { it.substringBefore("--") }
    }

    /** The quoted words of the first `IN (...)` after [constraint]'s name. */
    private fun wordsIn(constraint: String, column: String): Set<String> {
        val body = migration.substringAfter("CONSTRAINT $constraint", missingDelimiterValue = "")
        assertTrue("no constraint $constraint in V27", body.isNotEmpty())
        val list = Regex("""\b$column\s+IN\s*\(([^)]*)\)""").find(body)?.groupValues?.get(1)
            ?: error("$constraint has no '$column IN (...)'")
        return Regex("""'([^']*)'""").findAll(list).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `the key CHECK lists exactly the keys the server reads`() {
        assertEquals(
            SettingKey.entries.map { it.id }.toSet(),
            wordsIn("app_settings_key_known", "key"),
        )
    }

    @Test
    fun `the history knows the same keys`() {
        assertEquals(
            SettingKey.entries.map { it.id }.toSet(),
            wordsIn("app_settings_history_key_known", "key"),
        )
    }

    @Test
    fun `the gate's values are exactly on and off in both places`() {
        assertEquals(
            GateValue.entries.map { it.id }.toSet(),
            wordsIn("app_settings_value_valid", "value"),
        )
    }

    @Test
    fun `every key has a clause in the value CHECK`() {
        val body = migration.substringAfter("CONSTRAINT app_settings_value_valid")
            .substringBefore(");")
        SettingKey.entries.forEach { key ->
            assertTrue(
                "app_settings_value_valid has no clause for ${key.id}, so any value would do",
                body.contains("key = '${key.id}'"),
            )
        }
    }

    @Test
    fun `every audience is allowed in both tables`() {
        val audiences = Audience.entries.map { it.id }.toSet()
        assertEquals(audiences, wordsIn("app_settings_audience_known", "audience"))
        assertEquals(audiences, wordsIn("app_settings_history_audience_known", "audience"))
    }
}
