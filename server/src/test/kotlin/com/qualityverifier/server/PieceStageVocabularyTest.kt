package com.qualityverifier.server

import com.qualityverifier.domain.PieceStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V44's CHECKs and PieceStage agree (row 55). Read from the migration because the server tests
 * have no database, as ShopTabletVocabularyTest does. Both directions: a stage the phone sends
 * and the CHECK refuses would lose the fundi's answer; one the CHECK allows and no code sends is
 * a word nobody decided on.
 */
class PieceStageVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V44__piece_stage.sql")
        assertTrue("expected the migration at ${file.absolutePath}; the module root moved?", file.exists())
        file.readText().lines().joinToString("\n") { it.substringBefore("--") }
    }

    private fun wordsOf(constraint: String, column: String): Set<String> {
        val body = migration.substringAfter("CONSTRAINT $constraint")
        val list = Regex("""\b$column\s+IN\s*\(([^)]*)\)""").find(body)?.groupValues?.get(1)
            ?: error("$constraint has no '$column IN (...)'")
        return Regex("""'([^']*)'""").findAll(list).map { it.groupValues[1] }.toSet()
    }

    @Test
    fun `row 55 - a piece's stages match V44, on the piece and in its history`() {
        val ids = PieceStage.entries.map { it.id }.toSet()
        assertEquals(ids, wordsOf("pieces_stage_known", "stage"))
        assertEquals(ids, wordsOf("piece_stage_changes_old_known", "old_stage"))
        assertEquals(ids, wordsOf("piece_stage_changes_new_known", "new_stage"))
    }

    @Test
    fun `row 55 - the history is append-only, as V34's studio changes are`() {
        assertTrue(migration.contains("BEFORE UPDATE OR DELETE ON piece_stage_changes"))
        assertTrue(migration.contains("BEFORE TRUNCATE ON piece_stage_changes"))
        assertTrue(Regex("""pieces_stage_set_together\s+CHECK\s*\(\(stage IS NULL\) = \(stage_set_at IS NULL\)\)""").containsMatchIn(migration))
    }
}
