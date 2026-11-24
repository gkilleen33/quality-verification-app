package com.qualityverifier.server

import com.qualityverifier.domain.TabletEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V39's CHECKs and the Kotlin that writes the shop tablet's columns agree (row 12).
 *
 * Read from the migration because the server tests have no database, as ConsentVocabularyTest
 * does. Both directions: an end the tablet can send and the CHECK refuses would fail the
 * report and lose the research record; one the CHECK allows and no code sends is a word
 * nobody decided on.
 */
class ShopTabletVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V39__shop_tablet.sql")
        assertTrue("expected the migration at ${file.absolutePath}; the module root moved?", file.exists())
        file.readText().lines().joinToString("\n") { it.substringBefore("--") }
    }

    @Test
    fun `row 12 - the ways a tablet copy ends match V39`() {
        val body = migration.substringAfter("CONSTRAINT sessions_tablet_ended_known")
        val list = Regex("""\btablet_ended\s+IN\s*\(([^)]*)\)""").find(body)?.groupValues?.get(1)
            ?: error("sessions_tablet_ended_known has no 'tablet_ended IN (...)'")
        val words = Regex("""'([^']*)'""").findAll(list).map { it.groupValues[1] }.toSet()
        assertEquals(TabletEnd.entries.map { it.id }.toSet(), words)
    }

    @Test
    fun `row 12 - shop mode is Kagua's only, on the code and on the account`() {
        assertTrue(Regex("""invite_codes_shop_mode_kagua\s+CHECK\s*\(NOT grants_shop_mode OR audience = 'buyer'\)""").containsMatchIn(migration))
        assertTrue(Regex("""users_shop_mode_kagua\s+CHECK\s*\(NOT shop_mode OR audience = 'buyer'\)""").containsMatchIn(migration))
    }

    @Test
    fun `row 12 - registration copies shop mode from the code, and a session from its account`() {
        val auth = File("src/main/kotlin/com/qualityverifier/server/db/AuthStore.kt").readText()
        assertTrue("the invite read takes grants_shop_mode", auth.contains("grants_shop_mode from invite_codes"))
        val insert = Regex("""(?is)insert\s+into\s+users\s*\(([^)]*)\)""").find(auth)?.groupValues?.get(1).orEmpty()
        assertTrue("the users insert writes shop_mode", insert.contains("shop_mode"))

        val chat = File("src/main/kotlin/com/qualityverifier/server/db/ChatStore.kt").readText()
        val sessions = Regex("""(?is)insert\s+into\s+sessions\s*\(([^)]*)\)""").find(chat)?.groupValues?.get(1).orEmpty()
        assertTrue("the sessions insert writes shop_tablet", sessions.contains("shop_tablet"))
    }
}
