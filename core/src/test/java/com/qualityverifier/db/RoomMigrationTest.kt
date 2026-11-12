package com.qualityverifier.db

import androidx.sqlite.db.SupportSQLiteDatabase
import com.qualityverifier.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Proxy

/**
 * The phone's migrations against the schemas Room exported.
 *
 * These tests have no SQLite (the Android classes are stubs here), so a migration is run
 * against a recorder and its statements are read beside the exported JSON: the columns a
 * migration adds must be the columns the next schema has and the previous one lacks. A
 * migration that forgets a column fails here rather than at startup on a tester's phone,
 * where Room refuses to open the database. Running the SQL for real needs an instrumented
 * test with MigrationTestHelper, which the repo does not have.
 */
class RoomMigrationTest {

    private val schemas = File("schemas/com.qualityverifier.data.db.AppDatabase")

    private fun schema(version: Int): String {
        val file = File(schemas, "$version.json")
        assertTrue("expected the exported schema at ${file.absolutePath}", file.exists())
        return file.readText()
    }

    /** The CREATE TABLE statement Room exported for [table] at [version]. */
    private fun createSql(version: Int, table: String): String {
        val text = schema(version)
        val start = text.indexOf("\"tableName\": \"$table\"")
        assertTrue("no table $table in schema $version", start >= 0)
        return Regex("\"createSql\": \"(.*?)\",").find(text, start)!!.groupValues[1]
    }

    /** Runs [migration] against a database that only records what it is told to execute. */
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
        val steps = AppDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals((1 until CURRENT).map { it to it + 1 }, steps)
        assertTrue("schema $CURRENT.json must be committed with the version bump", File(schemas, "$CURRENT.json").exists())
    }

    @Test
    fun `13 to 14 adds the worker's number and kind, as schema 14 has them`() {
        val statements = statementsOf(AppDatabase.MIGRATION_13_14)
        assertEquals(
            listOf(
                "ALTER TABLE workers ADD COLUMN phone TEXT",
                "ALTER TABLE workers ADD COLUMN employment TEXT NOT NULL DEFAULT 'employee'",
            ),
            statements,
        )

        val before = createSql(13, "workers")
        val after = createSql(14, "workers")
        assertFalse(before, before.contains("`phone`"))
        assertFalse(before, before.contains("`employment`"))
        assertTrue(after, after.contains("`phone` TEXT"))
        // The default spelled in the entity and in the migration alike, or Room's schema
        // check fails at startup.
        assertTrue(after, after.contains("`employment` TEXT NOT NULL DEFAULT 'employee'"))

        // Nothing else changed between the two schemas: the other tables are as they were.
        listOf("sessions", "pieces", "session_workers", "mpesa_transactions").forEach { table ->
            assertEquals(table, createSql(13, table), createSql(14, table))
        }
    }

    @Test
    fun `14 to 15 adds the studio list and the linked mark, as schema 15 has them`() {
        val statements = statementsOf(AppDatabase.MIGRATION_14_15)
        assertEquals(2, statements.size)
        assertEquals("ALTER TABLE workers ADD COLUMN linked INTEGER NOT NULL DEFAULT 0", statements[0])
        // The CREATE TABLE as Room exported it, so the migrated table and a fresh one are
        // the same statement; Room's identity hash would otherwise refuse the database.
        assertEquals(createSql(15, "worker_studios").replace("\${TABLE_NAME}", "worker_studios"), statements[1])

        val before = createSql(14, "workers")
        val after = createSql(15, "workers")
        assertFalse(before, before.contains("`linked`"))
        assertTrue(after, after.contains("`linked` INTEGER NOT NULL DEFAULT 0"))
        listOf("sessions", "pieces", "session_workers", "mpesa_transactions").forEach { table ->
            assertEquals(table, createSql(14, table), createSql(15, table))
        }
    }

    private companion object {
        const val CURRENT = 15
    }
}
