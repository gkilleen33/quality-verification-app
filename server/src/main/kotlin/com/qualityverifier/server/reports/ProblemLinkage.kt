package com.qualityverifier.server.reports

import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.WorkPart

/**
 * Whether the problems Claude finds reach the workers who made the piece.
 *
 * The study measures a worker's skill through the problems found on the pieces they
 * worked on, so the pilot checks that the app links them (open items, "Problems linked to
 * workers"). A problem reaches a worker named in "Who worked on this?" (`session_workers`)
 * when the worker's part is the problem's default part ([DefaultParts]), or when the worker
 * was named for the whole piece (a null part), which counts as every part.
 *
 * A problem that reaches nobody gets one reason, the first that applies, in [Unlinked]'s
 * order. A problem with no part reaches nobody, not even a whole-piece worker: the table
 * leaves a problem unplaced exactly when its labels do not say whose work it was, and
 * material is usually the manager's purchase. The per-problem output carries
 * `whole_piece_named`, so the other reading can be counted from the same run.
 */
enum class Unlinked(val id: String) {
    /** "Who worked on this?" was skipped, or never asked: no row at all. */
    NOBODY_NAMED("nobody_named"),

    /** Every row names the part "other", which no problem is put down to. */
    ONLY_OTHER("only_other"),

    /** The problem's labels give it no part: material, or structural or level with no dimension. */
    NO_PART("no_part"),

    /** Workers are named on parts, and none of them is the problem's. */
    PART_MISMATCH("part_mismatch"),
}

/**
 * One row of "Who worked on this?". A null [workerId] is the manager ("Me"), who counts as
 * a worker like any other. A null [part] is "Whole piece".
 */
data class NamedWorker(val workerId: String?, val part: String?)

data class ProblemLink(val problem: ListedProblem, val unlinked: Unlinked?) {
    val linked: Boolean get() = unlinked == null
}

/** Each problem of one assessment, linked to [workers] or given the reason it is not. */
fun linkProblems(problems: List<ListedProblem>, workers: List<NamedWorker>): List<ProblemLink> {
    val parts = workers.map { it.part?.trim()?.lowercase() }
    // A worker on "other" is never linked: no problem is put down to "other", and a report
    // on "other" would be one nobody can hand to a person.
    val linkable = parts.filter { it != WorkPart.OTHER.id }
    return problems.map { problem ->
        val part = problem.defaultPart
        val reason = when {
            workers.isEmpty() -> Unlinked.NOBODY_NAMED
            linkable.isEmpty() -> Unlinked.ONLY_OTHER
            part == null -> Unlinked.NO_PART
            linkable.any { it == null || it == part.id } -> null
            else -> Unlinked.PART_MISMATCH
        }
        ProblemLink(problem, reason)
    }
}

internal val EVALUATION_HEADER = listOf(
    "session_id", "user_id", "piece_id", "created_at", "item_type_id", "purpose", "language",
    "by_tester", "deleted_by_user", "workers_named", "whole_piece_named",
    "problems", "problems_unknown", "linked", "share_linked",
    Unlinked.NOBODY_NAMED.id, Unlinked.ONLY_OTHER.id, Unlinked.NO_PART.id, Unlinked.PART_MISMATCH.id,
)

internal val PROBLEM_HEADER = listOf(
    "session_id", "created_at", "item_type_id", "purpose", "position", "title", "area",
    "severity", "dimension", "default_part", "whole_piece_named", "linked", "reason",
)

/** One row per assessment: how many of its problems reach a worker, and why the rest do not. */
internal fun evaluationRow(session: StoredSession, workers: List<NamedWorker>): List<Any?> {
    val intake = FundiIntake.decode(session.intakeAnswers)
    val list = DefectList.fromTexts(session.assistantTexts, intake?.second)
    val links = linkProblems(list.problems, workers)
    fun count(reason: Unlinked) = links.count { it.unlinked == reason }
    val linked = links.count { it.linked }
    return listOf(
        session.id, session.userId, session.pieceId, isoTime(session.createdAt), session.itemTypeId,
        intake?.second?.id, intake?.first?.code, session.byTester, session.deletedByUser,
        // People, not rows: one worker on joinery and finishing is two rows. The manager's
        // null id counts once.
        workers.map { it.workerId }.distinct().size, workers.any { it.part == null },
        // Blank rather than zero when unknown, so a sum over the column does not count
        // an unreadable reply as a clean piece.
        if (list.unknown) "" else links.size, list.unknown,
        if (list.unknown) "" else linked,
        if (list.unknown) "" else share(linked, links.size),
        count(Unlinked.NOBODY_NAMED), count(Unlinked.ONLY_OTHER),
        count(Unlinked.NO_PART), count(Unlinked.PART_MISMATCH),
    )
}

/** One row per problem, with its reason, for checking placements by hand. */
internal fun problemRows(session: StoredSession, workers: List<NamedWorker>): List<List<Any?>> {
    val intake = FundiIntake.decode(session.intakeAnswers)
    val list = DefectList.fromTexts(session.assistantTexts, intake?.second)
    val wholePiece = workers.any { it.part == null }
    return linkProblems(list.problems, workers).mapIndexed { index, link ->
        listOf(
            session.id, isoTime(session.createdAt), session.itemTypeId, intake?.second?.id,
            index + 1, link.problem.title, link.problem.area, link.problem.severity,
            link.problem.dimension, link.problem.defaultPart?.id, wholePiece, link.linked,
            link.unlinked?.id,
        )
    }
}

private const val USAGE = "ProblemLinkage [--since=YYYY-MM-DD] [--problems]"

/**
 * Prints the linkage measure as CSV on stdout: one row per Fundi Bora assessment, or with
 * `--problems` one row per problem. Every purpose is listed; filter on `purpose`. Reads
 * only, in a transaction that is rolled back. On the box:
 *
 *     java -cp /opt/kagua/kagua.jar com.qualityverifier.server.reports.ProblemLinkageKt \
 *          --since=2026-11-01 > linkage.csv
 *
 * with `KAGUA_DB_PASSWORD` in the environment, as for `admin/CreateAdmin.kt`.
 */
fun main(args: Array<String>) {
    val options = CommandArgs(USAGE, args, flags = setOf("--problems"))
    val perProblem = "--problems" in options.flags
    readOnly { connection ->
        val sessions = fundiSessions(connection, options.since)
        val workers = sessionWorkers(connection, options.since)
        println(csvRow(if (perProblem) PROBLEM_HEADER else EVALUATION_HEADER))
        sessions.forEach { session ->
            val named = workers[session.id].orEmpty()
            if (perProblem) problemRows(session, named).forEach { println(csvRow(it)) }
            else println(csvRow(evaluationRow(session, named)))
        }
    }
}

/** Every "Who worked on this?" row of the Fundi Bora sessions [fundiSessions] reads, by session. */
private fun sessionWorkers(connection: java.sql.Connection, since: Long?): Map<String, List<NamedWorker>> =
    connection.prepareStatement(
        """
        select sw.session_id::text, sw.worker_id::text, sw.part
          from session_workers sw join sessions s on s.id = sw.session_id
         where s.audience = 'fundi'
           and (?::bigint is null or s.created_at >= to_timestamp(?::bigint / 1000.0))
         order by sw.session_id, sw.id
        """.trimIndent()
    ).use { statement ->
        setSince(statement, since)
        statement.executeQuery().use { rows ->
            val out = mutableMapOf<String, MutableList<NamedWorker>>()
            while (rows.next()) {
                out.getOrPut(rows.getString(1)) { mutableListOf() } +=
                    NamedWorker(workerId = rows.getString(2), part = rows.getString(3))
            }
            out
        }
    }
