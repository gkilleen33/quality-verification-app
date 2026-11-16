package com.qualityverifier.server

import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import com.qualityverifier.mpesa.MobileMoneyParser
import com.qualityverifier.server.routes.TRACKER_CODE_SHAPES
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * V33's CHECKs and the Kotlin vocabularies PesaLog writes list the same words, in both
 * directions, as MpesaVocabularyTest does for V29. Read from the migration because the
 * server tests have no database.
 */
class TrackerVocabularyTest {

    private val migration: String by lazy {
        val file = File("db/migrations/V33__tracker.sql")
        assertTrue("expected the migration at ${file.absolutePath}; the module root moved?", file.exists())
        file.readText().lines().joinToString("\n") { it.substringBefore("--") }
    }

    private fun constraint(name: String): String {
        val body = migration.substringAfter("CONSTRAINT $name", missingDelimiterValue = "")
        assertTrue("no constraint $name in V33", body.isNotEmpty())
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
    fun `networks match, in both tables`() {
        val networks = MobileMoneyNetwork.entries.map { it.id }.toSet()
        assertEquals(networks, wordsIn("tracker_transactions_network_known", "network"))
        assertEquals(networks, wordsIn("tracker_unread_counts_network_known", "network"))
    }

    @Test
    fun `every network is one the parser reads, under the parser's own name`() {
        assertEquals(
            setOf(MobileMoneyParser.NETWORK_MPESA, MobileMoneyParser.NETWORK_AIRTEL),
            MobileMoneyNetwork.entries.map { it.id }.toSet(),
        )
    }

    @Test
    fun `each network's code shape matches the route's`() {
        val shape = constraint("tracker_transactions_code_shape")
        MobileMoneyNetwork.entries.forEach { network ->
            val pattern = Regex("""network\s*=\s*'${network.id}'\s+AND\s+code\s*~\s*'([^']+)'""").find(shape)
                ?.groupValues?.get(1)
            assertEquals("code shape for ${network.id}", TRACKER_CODE_SHAPES.getValue(network).pattern, pattern)
        }
    }

    @Test
    fun `directions, types, categories and bases match V29's vocabularies`() {
        assertEquals(MpesaDirection.entries.map { it.id }.toSet(), wordsIn("tracker_transactions_direction_known", "direction"))
        assertEquals(MpesaType.entries.map { it.id }.toSet(), wordsIn("tracker_transactions_type_known", "tx_type"))
        assertEquals(MpesaCategory.entries.map { it.id }.toSet(), wordsIn("tracker_transactions_category_known", "category"))
        assertEquals(
            CounterpartyBasis.entries.map { it.id }.toSet(),
            wordsIn("tracker_transactions_basis_known", "counterparty_basis"),
        )
    }

    @Test
    fun `the categories each direction allows match`() {
        val fits = constraint("tracker_transactions_category_fits_direction")
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
