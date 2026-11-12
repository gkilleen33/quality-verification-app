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
    fun `anonymise_user clears every phone column`() {
        // anonymise_user is redefined whole each time it changes, so the latest definition is
        // the one that runs. A column added later that holds a number (V21's
        // phone_as_entered was the first) and is missing from it would survive deletion,
        // which is the one thing V11 promises cannot happen.
        val phoneColumns = files
            // IF NOT EXISTS too: half the migrations since V13 add columns that way.
            .flatMap {
                Regex("""(?i)ADD COLUMN\s+(?:IF NOT EXISTS\s+)?(\w*phone\w*)""")
                    .findAll(it.readText()).toList()
            }
            .map { it.groupValues[1] }
            .toSet()
        assertTrue("expected at least users.phone, found none", "phone" in phoneColumns)

        val latest = files.last { it.readText().contains("FUNCTION anonymise_user") }
        val body = latest.readText().substringAfter("FUNCTION anonymise_user")
        phoneColumns.forEach { column ->
            assertTrue(
                "${latest.name} defines anonymise_user without clearing $column, so a deleted " +
                    "account keeps that number",
                Regex("""\b$column\s*=\s*NULL""").containsMatchIn(body),
            )
        }
    }

    /**
     * Every column that holds something personal and is not a phone number, by name.
     * The phone test above finds its columns by pattern; these have no pattern, so each
     * PR that adds one adds it here, and a later rewrite of anonymise_user that forgets
     * it fails here rather than on a deleted account.
     */
    private val personalColumns = listOf(
        "users" to "display_name",
        "users" to "business_name",
        "users" to "business_location",
        "sessions" to "location",
        // V22: the maker's name for a piece, often the customer's name.
        "pieces" to "label",
        // V23: a worker's nickname.
        "workers" to "name",
        // V30: a worker's number. Found by the phone test above too; named here so the
        // UPDATE on workers is checked as a statement, not only as a column somewhere.
        "workers" to "phone",
        // V31: a studio's name and its owner's number, on the worker's own list. Made with
        // CREATE TABLE, which the phone-column pattern does not see.
        "worker_studios" to "name",
        "worker_studios" to "phone",
        // V25: a photo's GPS point, serial numbers, and owner and artist names.
        "attachments" to "photo_meta",
        // V32: the worker names and the maker's name a report printed.
        "reports" to "printed_names",
    )

    /**
     * A report's snapshot holds the names it printed beside the verdict text (V32), and is
     * NOT NULL, so it is emptied rather than nulled; the personal-column test above cannot
     * see that, so it is pinned here.
     */
    @Test
    fun `anonymise_user empties the snapshot of the account's reports`() {
        val latest = files.last { it.readText().contains("FUNCTION anonymise_user") }
        val body = latest.readText().substringAfter("FUNCTION anonymise_user")
            .lines().joinToString(" ") { it.substringBefore("--") }
            .replace(Regex("""\s+"""), " ")
        assertTrue(
            "${latest.name} defines anonymise_user without emptying reports.defect_snapshot",
            Regex("""(?i)UPDATE reports SET defect_snapshot = '\{\}'::jsonb, printed_names = NULL WHERE user_id = target;""")
                .containsMatchIn(body),
        )
    }

    @Test
    fun `anonymise_user clears every named personal column`() {
        val latest = files.last { it.readText().contains("FUNCTION anonymise_user") }
        val body = latest.readText().substringAfter("FUNCTION anonymise_user")
            // Comments out, so a WHERE in one cannot end a SET list early.
            .lines().joinToString("\n") { it.substringBefore("--") }
        personalColumns.forEach { (table, column) ->
            // An UPDATE on that table whose SET list, up to its WHERE, nulls the column.
            val statements = Regex("""(?is)UPDATE\s+$table\s+SET\b(.*?)\bWHERE\b""")
                .findAll(body).map { it.groupValues[1] }.toList()
            assertTrue(
                "${latest.name} defines anonymise_user without an UPDATE on $table",
                statements.isNotEmpty(),
            )
            assertTrue(
                "${latest.name} defines anonymise_user without clearing $table.$column",
                statements.any { Regex("""\b$column\s*=\s*NULL""").containsMatchIn(it) },
            )
        }
    }

    /**
     * Tables whose rows anonymise_user deletes whole rather than blanking column by column.
     * Each PR that adds one adds it here.
     */
    private val deletedTables = listOf(
        // V29: a fundi's M-Pesa transactions, with free-text comments, and unread counts.
        "mpesa_transactions",
        "mpesa_unread_counts",
    )

    @Test
    fun `anonymise_user deletes the account's rows from every table it empties`() {
        val latest = files.last { it.readText().contains("FUNCTION anonymise_user") }
        val body = latest.readText().substringAfter("FUNCTION anonymise_user")
            .lines().joinToString(" ") { it.substringBefore("--") }
            .replace(Regex("""\s+"""), " ")
        deletedTables.forEach { table ->
            assertTrue(
                "${latest.name} defines anonymise_user without deleting the account's rows from $table",
                Regex("""(?i)DELETE FROM $table WHERE user_id = target;""").containsMatchIn(body),
            )
        }
    }

    /**
     * Each redefinition of anonymise_user keeps everything the one before it did.
     *
     * The function is replaced whole every time it changes, so the only way to lose a
     * clause is to retype the body and miss one. Compared as assignments rather than as
     * statements, so a definition may add a column to an existing UPDATE, as V21 added
     * phone_as_entered to the users statement.
     */
    @Test
    fun `anonymise_user keeps every clause of the previous definition`() {
        val definitions = files
            .filter { it.readText().contains("FUNCTION anonymise_user") }
            .map { it.name to clausesOf(it.readText().substringAfter("FUNCTION anonymise_user")) }
        assertTrue("expected V11, V13, V21 and V22 at least", definitions.size >= 4)
        definitions.zipWithNext().forEach { (earlier, later) ->
            val lost = earlier.second - later.second
            assertTrue(
                "${later.first} drops these clauses that ${earlier.first} had: $lost",
                lost.isEmpty(),
            )
        }
    }

    /**
     * A deleted account's tester invite code cannot register a second account (V26, D6).
     *
     * The unique index on users.invite_code is what makes a code single use, and
     * anonymise_user blanks that column. So the function revokes the code, and does it
     * before the UPDATE on users, while users.invite_code still names it. Revoked the other
     * way round, the subquery finds NULL and nothing is revoked, with no error.
     *
     * Read from the migration because the server tests have no database; AuthTest holds
     * register() to refusing a revoked code.
     */
    @Test
    fun `anonymise_user revokes the account's invite code before blanking it`() {
        val latest = files.last { it.readText().contains("FUNCTION anonymise_user") }
        val body = latest.readText().substringAfter("FUNCTION anonymise_user")
            .lines().joinToString(" ") { it.substringBefore("--") }
            .replace(Regex("""\s+"""), " ")

        val revoke = Regex("""(?i)UPDATE invite_codes SET revoked_at = now\(\) WHERE (.*?);""")
            .find(body)
        assertTrue(
            "${latest.name} defines anonymise_user without revoking the account's invite code, " +
                "so the code registers a new account once the account is deleted",
            revoke != null,
        )
        val where = revoke!!.groupValues[1]
        assertTrue(
            "${latest.name} revokes invite codes without picking the target's own: $where",
            Regex("""(?i)\binvite_code FROM users\b.*\bid = target\b""").containsMatchIn(where),
        )
        assertTrue(
            "${latest.name} overwrites the date an admin already revoked the code on",
            Regex("""(?i)\brevoked_at IS NULL\b""").containsMatchIn(where),
        )

        val blank = Regex("""(?i)UPDATE users SET\b""").find(body)
        assertTrue("${latest.name} defines anonymise_user without an UPDATE on users", blank != null)
        assertTrue(
            "${latest.name} revokes the invite code after blanking users.invite_code, so the " +
                "subquery finds nothing to revoke",
            revoke.range.first < blank!!.range.first,
        )
    }

    /** Every (table, column, value, where) of each UPDATE, and every DELETE whole. */
    private fun clausesOf(body: String): Set<String> {
        val text = body.lines().joinToString(" ") { it.substringBefore("--") }
            .replace(Regex("""\s+"""), " ")
        val updates = Regex("""(?i)UPDATE (\w+) SET (.*?) WHERE (.*?);""").findAll(text).flatMap { m ->
            val (table, assignments, where) = m.destructured
            splitAssignments(assignments).map { assignment ->
                val (column, value) = assignment.split("=", limit = 2).map { it.trim() }
                "$table.$column = $value where ${where.trim()}"
            }.asSequence()
        }
        val deletes = Regex("""(?i)DELETE FROM .*?;""").findAll(text).map { it.value.trim() }
        return (updates + deletes).toSet()
    }

    /** Splits a SET list on the commas between assignments, not those inside a call. */
    private fun splitAssignments(list: String): List<String> {
        val out = mutableListOf<String>()
        var depth = 0
        val current = StringBuilder()
        list.forEach { c ->
            when {
                c == '(' -> { depth++; current.append(c) }
                c == ')' -> { depth--; current.append(c) }
                c == ',' && depth == 0 -> { out += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        if (current.isNotBlank()) out += current.toString()
        return out
    }

    /**
     * Tables whose rows are a record and must never change, by the migration that makes them.
     * Each PR that adds one adds it here.
     */
    private val appendOnlyTables = listOf(
        // V27: who changed a setting, when, and why.
        "V27__app_settings.sql" to "app_settings_history",
        // V28: each agreement to the terms, and each withdrawal.
        "V28__consents.sql" to "consents",
        // V31: each link's start and end.
        "V31__worker_studios.sql" to "worker_link_events",
    )

    /**
     * The match (V31) is a view, never a stored copy that can go stale, and nothing
     * computes it by trigger: the record of starts and ends is written by a function the
     * store calls in each write's transaction, and anonymise_user calls after clearing the
     * numbers. Read from the file, as the other migration rules are.
     */
    @Test
    fun `the worker match is a view, recorded by a function and not by a trigger`() {
        val text = files.first { it.name == "V31__worker_studios.sql" }.readText()
            .lines().joinToString(" ") { it.substringBefore("--") }.replace(Regex("""\s+"""), " ")
        assertTrue("V31 has no worker_links view", Regex("""(?i)CREATE VIEW worker_links AS""").containsMatchIn(text))
        assertTrue("V31 stores the match", !Regex("""(?i)CREATE TABLE worker_links\b""").containsMatchIn(text))
        val triggers = Regex("""(?i)CREATE TRIGGER \w+ (BEFORE|AFTER) ([\w ]+?) ON (\w+)""").findAll(text)
            .map { it.groupValues[3] to it.groupValues[2].trim() }.toList()
        assertEquals(
            "the only triggers are the append-only guards on the record",
            listOf("worker_link_events" to "UPDATE OR DELETE", "worker_link_events" to "TRUNCATE"),
            triggers,
        )
        val anonymise = text.substringAfter("FUNCTION anonymise_user")
        assertTrue(
            "anonymise_user must record the links that deletion ends",
            Regex("""(?i)PERFORM sync_worker_link_events\(\);""").containsMatchIn(anonymise),
        )
    }

    /**
     * Each append-only table refuses UPDATE and DELETE row by row and TRUNCATE by statement.
     *
     * Row triggers do not fire on TRUNCATE, so a table with only the first is emptied by one
     * statement. Read from the file because the tests have no database; the migration's own
     * self-test proves the triggers fire, in the deploy rehearsal.
     */
    @Test
    fun `append-only tables refuse update, delete and truncate`() {
        appendOnlyTables.forEach { (name, table) ->
            val file = files.firstOrNull { it.name == name } ?: error("no migration $name")
            val text = file.readText().replace(Regex("""\s+"""), " ")
            assertTrue(
                "$name has no trigger refusing UPDATE and DELETE on $table",
                Regex("""(?i)BEFORE UPDATE OR DELETE ON $table FOR EACH ROW EXECUTE FUNCTION refuse_row_change\(\)""")
                    .containsMatchIn(text),
            )
            assertTrue(
                "$name has no trigger refusing TRUNCATE on $table",
                Regex("""(?i)BEFORE TRUNCATE ON $table FOR EACH STATEMENT EXECUTE FUNCTION refuse_row_change\(\)""")
                    .containsMatchIn(text),
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
