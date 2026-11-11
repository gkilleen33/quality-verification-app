package com.qualityverifier.server

import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V29's CHECKs and the shared M-Pesa enums list the same words, in both directions, as
 * ConsentVocabularyTest does for V28: a value the phone can send and the CHECK refuses
 * fails a whole upload batch, and a value the CHECK allows and no code writes is a
 * vocabulary nobody decided on. Read from the migration because the server tests have no
 * database.
 */
class MpesaVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V29__mpesa.sql")
        assertTrue("expected the migration at ${file.absolutePath}; the module root moved?", file.exists())
        file.readText().lines().joinToString("\n") { it.substringBefore("--") }
    }

    /** The text of one CHECK, up to the next CONSTRAINT or the end of the table. */
    private fun constraint(name: String): String {
        val body = migration.substringAfter("CONSTRAINT $name", missingDelimiterValue = "")
        assertTrue("no constraint $name in V29", body.isNotEmpty())
        return body.substringBefore("CONSTRAINT ").substringBefore(");\n")
    }

    private fun quoted(text: String): Set<String> =
        Regex("""'([^']*)'""").findAll(text).map { it.groupValues[1] }.toSet()

    private fun wordsIn(name: String, column: String): Set<String> {
        val list = Regex("""\b$column\s+IN\s*\(([^)]*)\)""").find(constraint(name))?.groupValues?.get(1)
            ?: error("$name has no '$column IN (...)'")
        return quoted(list)
    }

    @Test
    fun `directions match`() {
        assertEquals(
            MpesaDirection.entries.map { it.id }.toSet(),
            wordsIn("mpesa_transactions_direction_known", "direction"),
        )
    }

    @Test
    fun `types match`() {
        assertEquals(MpesaType.entries.map { it.id }.toSet(), wordsIn("mpesa_transactions_type_known", "tx_type"))
    }

    @Test
    fun `categories match`() {
        assertEquals(
            MpesaCategory.entries.map { it.id }.toSet(),
            wordsIn("mpesa_transactions_category_known", "category"),
        )
    }

    @Test
    fun `code bases match`() {
        assertEquals(
            CounterpartyBasis.entries.map { it.id }.toSet(),
            wordsIn("mpesa_transactions_basis_known", "counterparty_basis"),
        )
    }

    /**
     * Which categories each direction allows, read clause by clause from
     * mpesa_transactions_category_fits_direction: "other" for every direction, then one
     * clause per direction listing its own.
     */
    @Test
    fun `the categories each direction allows match`() {
        val fits = constraint("mpesa_transactions_category_fits_direction")
        val everywhere = Regex("""\bcategory\s*=\s*'([^']+)'""").findAll(
            fits.substringBefore("(direction"),
        ).map { it.groupValues[1] }.toSet()
        MpesaDirection.entries.forEach { direction ->
            val clause = Regex("""\(direction\s*=\s*'${direction.id}'\s+AND\s+category\s+(?:IN\s*\(([^)]*)\)|=\s*'([^']+)')""")
                .find(fits)
            val own = clause?.let { quoted(it.value.substringAfter("AND")) }.orEmpty()
            assertEquals(
                "categories for ${direction.id}",
                MpesaCategory.choicesFor(direction).map { it.id }.toSet(),
                everywhere + own,
            )
        }
    }
}
