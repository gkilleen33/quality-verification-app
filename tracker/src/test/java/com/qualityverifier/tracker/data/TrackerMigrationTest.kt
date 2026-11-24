package com.qualityverifier.tracker.data

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * PesaLog's migrations against the schemas Room exported, as :core's RoomMigrationTest checks
 * Kagua's and Fundi Bora's: no SQLite here, so each migration runs against a recorder and its
 * statements are read beside the exported JSON.
 */
class TrackerMigrationTest {

    private val schemas = File("schemas/com.qualityverifier.tracker.data.TrackerDatabase")

    private fun createSql(version: Int, table: String): String {
        val file = File(schemas, "$version.json")
        assertTrue("expected the exported schema at ${file.absolutePath}", file.exists())
        val text = file.readText()
        val start = text.indexOf("\"tableName\": \"$table\"")
        assertTrue("no table $table in schema $version", start >= 0)
        return Regex("\"createSql\": \"(.*?)\",").find(text, start)!!.groupValues[1]
    }

    private fun statementsOf(migration: androidx.room.migration.Migration): List<String> {
        val executed = mutableListOf<String>()
        val recorder = Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            if (method.name == "execSQL") executed += args[0] as String
            null
        } as SupportSQLiteDatabase
        migration.migrate(recorder)
        return executed
    }

    @Test
    fun `the migrations run from 1 to the current version with no gap`() {
        val steps = TrackerDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until CURRENT).map { it to it + 1 }, steps)
        assertTrue("schema $CURRENT.json must be committed with the version bump", File(schemas, "$CURRENT.json").exists())
    }

    @Test
    fun `row 32 - 1 to 2 adds the masked number, as schema 2 has it`() {
        assertEquals(
            listOf("ALTER TABLE transactions ADD COLUMN counterpartyMasked TEXT"),
            statementsOf(TrackerDatabase.MIGRATION_1_2),
        )
        val before = createSql(1, "transactions")
        val after = createSql(2, "transactions")
        assertFalse(before, before.contains("`counterpartyMasked`"))
        assertTrue(after, after.contains("`counterpartyMasked` TEXT"))
        listOf("unread_days", "counted_unread").forEach { table ->
            assertEquals(table, createSql(1, table), createSql(2, table))
        }
    }

    private companion object {
        const val CURRENT = 2
    }
}
