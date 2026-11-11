package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.terms.ConsentAction
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.server.terms.TermsLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V28's CHECKs and the Kotlin vocabularies that write `consents` list the same words.
 *
 * Read from the migration because the server tests have no database, as
 * FundiVocabularyTest and SettingsVocabularyTest do. Both directions: a kind the code can
 * write and the CHECK refuses fails an agreement at the moment a maker taps Continue; a
 * kind the CHECK allows and no code writes is a vocabulary nobody decided on.
 */
class ConsentVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V28__consents.sql")
        assertTrue("expected the migration at ${file.absolutePath}; the module root moved?", file.exists())
        file.readText().lines().joinToString("\n") { it.substringBefore("--") }
    }

    private fun wordsIn(constraint: String, column: String): Set<String> {
        val body = migration.substringAfter("CONSTRAINT $constraint", missingDelimiterValue = "")
        assertTrue("no constraint $constraint in V28", body.isNotEmpty())
        val list = Regex("""\b$column\s+IN\s*\(([^)]*)\)""").find(body)?.groupValues?.get(1)
            ?: error("$constraint has no '$column IN (...)'")
        return Regex("""'([^']*)'""").findAll(list).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `kinds match`() {
        assertEquals(ConsentKind.entries.map { it.id }.toSet(), wordsIn("consents_kind_known", "kind"))
    }

    @Test
    fun `actions match`() {
        assertEquals(ConsentAction.entries.map { it.id }.toSet(), wordsIn("consents_action_known", "action"))
    }

    @Test
    fun `languages match`() {
        assertEquals(TermsLanguage.entries.map { it.id }.toSet(), wordsIn("consents_language_known", "language"))
    }

    @Test
    fun `audiences match`() {
        assertEquals(Audience.entries.map { it.id }.toSet(), wordsIn("consents_audience_known", "audience"))
    }

    @Test
    fun `only the transfer agreement can be withdrawn, in both places`() {
        assertTrue(
            Regex("""action\s*=\s*'agreed'\s+OR\s+kind\s*=\s*'transfer'""")
                .containsMatchIn(migration.substringAfter("CONSTRAINT consents_only_transfer_withdrawn")),
        )
    }

    @Test
    fun `every insert names its audience`() {
        // AuthStore's rule for users, applied here: an audience is never defaulted.
        val store = File("src/main/kotlin/com/qualityverifier/server/terms/ConsentStore.kt").readText()
        val inserts = Regex("""(?is)insert\s+into\s+consents\s*\(([^)]*)\)""").findAll(store).toList()
        assertTrue("found no inserts into consents", inserts.isNotEmpty())
        inserts.forEach { assertTrue("an insert into consents omits audience", it.groupValues[1].contains("audience")) }
    }
}
