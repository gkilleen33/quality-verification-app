package com.qualityverifier.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The migration files themselves.
 *
 * apply.sh decides what to skip by reading schema_migrations, so a migration that does not
 * insert its own version is never marked applied. V7 and V8 both shipped without one, and
 * nothing caught it: the test suite never looked at these files, and the failure only shows
 * up on the *second* apply, by which point the first has already succeeded and the script
 * exits non-zero for what looks like no reason.
 */
class MigrationsTest {

    private val files: List<File> =
        File("db/migrations").listFiles { f -> f.name.endsWith(".sql") }
            // By version number, not by name. Sorting lexicographically puts V10 before V2,
            // which is what this test did until there were ten migrations. apply.sh uses
            // `sort -V` and has always been right; the test was the thing that was wrong.
            ?.sortedBy { versionOf(it) }
            ?: error("no migrations found; expected server/db/migrations relative to the module")

    private fun versionOf(file: File): Int =
        file.name.substringAfter("V").substringBefore("__").toInt()

    @Test
    fun `there are migrations to check`() {
        // Guards the test itself: a wrong path would otherwise make everything below pass
        // by looking at nothing.
        assertTrue("expected several migrations, found ${files.size}", files.size >= 8)
    }

    @Test
    fun `every migration records its own version`() {
        files.forEach { file ->
            val version = file.name.removeSuffix(".sql")
            val text = file.readText()
            assertTrue(
                "${file.name} never inserts '$version' into schema_migrations, so apply.sh " +
                    "will run it again on the next deploy",
                text.contains("VALUES ('$version')"),
            )
        }
    }

    @Test
    fun `every migration is one transaction`() {
        // Half an applied migration is the worst outcome: the next run skips it if the
        // version row landed, and repeats it if not.
        files.forEach { file ->
            val text = file.readText()
            assertTrue("${file.name} has no BEGIN", text.contains("BEGIN;"))
            assertTrue("${file.name} has no COMMIT", text.contains("COMMIT;"))
            assertTrue(
                "${file.name} records its version outside the transaction",
                text.indexOf("VALUES ('") < text.lastIndexOf("COMMIT;"),
            )
        }
    }

    @Test
    fun `versions are sequential with no gaps or repeats`() {
        // A gap usually means a file was renamed and the old version is still recorded in
        // schema_migrations on a deployed box, which makes the two disagree silently.
        assertEquals((1..files.size).toList(), files.map(::versionOf))
    }
}
