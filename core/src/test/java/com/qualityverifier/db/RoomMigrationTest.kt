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

    @Test
    fun `15 to 16 adds the studio label, the final flag and today's studio, as schema 16 has them`() {
        val statements = statementsOf(AppDatabase.MIGRATION_15_16)
        // The CREATE TABLE as Room exported it, as for the studio list above.
        assertEquals(createSql(16, "studio_days").replace("\${TABLE_NAME}", "studio_days"), statements.last())
        // Each column added is in schema 16's table, with the same default.
        val pieces = createSql(16, "pieces")
        listOf(
            "`studioId` TEXT", "`studioChosen` INTEGER NOT NULL DEFAULT 0",
            "`studioCorrected` INTEGER NOT NULL DEFAULT 0", "`studioPendingAt` INTEGER", "`mine` INTEGER NOT NULL DEFAULT 1",
        ).forEach { column ->
            assertTrue(column, pieces.contains(column))
            assertTrue(column, statements.contains("ALTER TABLE pieces ADD COLUMN ${column.replace("`", "")}"))
        }
        assertTrue(createSql(16, "sessions").contains("`isFinal` INTEGER NOT NULL DEFAULT 0"))
        assertTrue(statements.contains("ALTER TABLE sessions ADD COLUMN isFinal INTEGER NOT NULL DEFAULT 0"))
        listOf("workers", "worker_studios", "session_workers", "mpesa_transactions").forEach { table ->
            assertEquals(table, createSql(15, table), createSql(16, table))
        }
    }

    @Test
    fun `16 to 17 adds the tester's review answers and each photo's number, as schema 17 has them`() {
        val statements = statementsOf(AppDatabase.MIGRATION_16_17)
        assertEquals(
            listOf(
                "ALTER TABLE attachments ADD COLUMN photoNumber INTEGER",
                createSql(17, "review_helpful").replace("\${TABLE_NAME}", "review_helpful"),
            ),
            statements,
        )
        assertTrue(createSql(17, "attachments").contains("`photoNumber` INTEGER"))
        listOf("sessions", "pieces", "workers", "worker_studios", "studio_days", "pending_tester_feedback").forEach { table ->
            assertEquals(table, createSql(16, table), createSql(17, table))
        }
    }

    @Test
    fun `row 17 - 17 to 18 drops the two M-Pesa tables, as schema 18 lacks them`() {
        assertEquals(
            listOf(
                "DROP TABLE IF EXISTS `mpesa_transactions`",
                "DROP TABLE IF EXISTS `mpesa_unread_days`",
            ),
            statementsOf(AppDatabase.MIGRATION_17_18),
        )
        val before = schema(17)
        val after = schema(18)
        listOf("mpesa_transactions", "mpesa_unread_days").forEach { table ->
            assertTrue(table, before.contains("\"tableName\": \"$table\""))
            assertFalse(table, after.contains("\"tableName\": \"$table\""))
        }
        // Every other table is as it was.
        listOf(
            "sessions", "messages", "attachments", "pieces", "workers", "worker_studios", "session_workers",
            "pending_session_workers", "studio_days", "review_helpful", "pending_tester_feedback",
            "pending_remote_deletes", "dismissed_sessions",
        ).forEach { table ->
            assertEquals(table, createSql(17, table), createSql(18, table))
        }
    }

    private companion object {
        const val CURRENT = 18
    }
}
