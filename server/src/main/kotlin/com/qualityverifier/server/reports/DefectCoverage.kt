package com.qualityverifier.server.reports

import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.text.AssistantContent
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.writesConsequences

/**
 * How far a Fundi Bora reply's two lists of problems disagree, in today's shape.
 *
 * Today's prompt writes each problem twice: as a diagnosis finding and as a verdict defect
 * "using the same titles as your diagnosis", linked only by title. This counts the verdict
 * defects that no finding shares a title with, the findings no defect shares one with, and
 * how often the two lists differ in length. It is the evidence that writing each problem
 * once (v0.4.0 PR 16) removes a real mismatch, and the baseline for the defect count's
 * change of meaning: the verdict's defects before that change, the findings after.
 *
 * The session's newest verdict and newest diagnosis are compared, as [DefectList] reads
 * them, because that pair is what the readers and the count use.
 */
enum class CoverageShape(val id: String) {
    /** A verdict and a readable diagnosis in today's shape: the case measured. */
    TODAY("today"),

    /** The newest diagnosis writes each problem once; nothing to compare. */
    ONE_LIST("one_list"),

    /** No verdict: a partial check, or a full assessment abandoned before one. */
    NO_VERDICT("no_verdict"),

    /** A verdict with no diagnosis anywhere in the session. */
    NO_DIAGNOSIS("no_diagnosis"),

    /** The newest diagnosis block would not decode, so its findings are unknown. */
    DIAGNOSIS_UNREADABLE("diagnosis_unreadable"),
}

data class SessionCoverage(
    val shape: CoverageShape,
    val defects: Int,
    val findings: Int,
    /** Verdict defects with no finding of the same title. Counted for [CoverageShape.TODAY] only. */
    val defectsWithoutFinding: Int,
    /** Findings with no verdict defect of the same title. Counted for [CoverageShape.TODAY] only. */
    val findingsWithoutDefect: Int,
) {
    val lengthsDiffer: Boolean get() = shape == CoverageShape.TODAY && defects != findings
}

/** The two lists of one session compared, from its replies parsed, newest first. */
fun coverageOf(repliesNewestFirst: List<AssistantContent>): SessionCoverage {
    val newest = NewestBlocks.of(repliesNewestFirst)
    val verdict = newest.verdict
    val findings = newest.diagnosis?.findings.orEmpty()
    val defects = verdict?.defects.orEmpty()
    val shape = when {
        verdict == null -> CoverageShape.NO_VERDICT
        !newest.sentDiagnosis -> CoverageShape.NO_DIAGNOSIS
        newest.diagnosisUnreadable -> CoverageShape.DIAGNOSIS_UNREADABLE
        newest.diagnosis?.writesConsequences == true -> CoverageShape.ONE_LIST
        // Includes a diagnosis that found nothing beside a verdict: zero findings, and any
        // defect it lists is one with no finding.
        else -> CoverageShape.TODAY
    }
    if (shape != CoverageShape.TODAY) {
        return SessionCoverage(shape, defects.size, findings.size, 0, 0)
    }
    // problemsOf's own match: trimmed and case-insensitive. Blank titles are left out of
    // both sets, so a blank title matches nothing.
    val findingTitles = findings.map { titleKey(it.title) }.filter { it.isNotEmpty() }.toSet()
    val defectTitles = defects.map { titleKey(it.title) }.filter { it.isNotEmpty() }.toSet()
    return SessionCoverage(
        shape = shape,
        defects = defects.size,
        findings = findings.size,
        defectsWithoutFinding = defects.count { titleKey(it.title) !in findingTitles },
        findingsWithoutDefect = findings.count { titleKey(it.title) !in defectTitles },
    )
}

private fun titleKey(title: String): String = title.trim().lowercase()

/** The totals `--summary` prints, over full assessments only. */
data class CoverageSummary(
    val sessions: Int,
    val fullAssessments: Int,
    val byShape: Map<CoverageShape, Int>,
    val lengthsDiffer: Int,
    val defects: Int,
    val defectsWithoutFinding: Int,
    val findings: Int,
    val findingsWithoutDefect: Int,
    /** Sessions whose intake code this server cannot read, left out of the totals. */
    val purposeNotKnown: Int,
) {
    fun rows(): List<List<Any?>> = buildList {
        add(listOf("sessions", sessions))
        add(listOf("purpose_not_known", purposeNotKnown))
        add(listOf("full_assessments", fullAssessments))
        CoverageShape.entries.forEach { add(listOf("full_assessments_${it.id}", byShape[it] ?: 0)) }
        add(listOf("today_lengths_differ", lengthsDiffer))
        add(listOf("today_verdict_defects", defects))
        add(listOf("today_defects_without_finding", defectsWithoutFinding))
        add(listOf("today_findings", findings))
        add(listOf("today_findings_without_defect", findingsWithoutDefect))
    }

    companion object {
        /**
         * Totals over the full assessments ([com.qualityverifier.domain.FundiPurpose.fullAssessment]);
         * the defect and finding counts over those in today's shape. Each of [sessions] pairs
         * the session's `fullAssessment`, or null when its purpose is not known, with its
         * coverage.
         */
        fun of(sessions: List<Pair<Boolean?, SessionCoverage>>): CoverageSummary {
            val full = sessions.filter { it.first == true }.map { it.second }
            val today = full.filter { it.shape == CoverageShape.TODAY }
            return CoverageSummary(
                sessions = sessions.size,
                fullAssessments = full.size,
                byShape = full.groupingBy { it.shape }.eachCount(),
                lengthsDiffer = today.count { it.lengthsDiffer },
                defects = today.sumOf { it.defects },
                defectsWithoutFinding = today.sumOf { it.defectsWithoutFinding },
                findings = today.sumOf { it.findings },
                findingsWithoutDefect = today.sumOf { it.findingsWithoutDefect },
                purposeNotKnown = sessions.count { it.first == null },
            )
        }
    }
}

internal val COVERAGE_HEADER = listOf(
    "session_id", "created_at", "item_type_id", "purpose", "full_assessment", "shape",
    "verdict_defects", "findings", "defects_without_finding", "findings_without_defect",
    "lengths_differ",
)

private const val USAGE = "DefectCoverage [--since=YYYY-MM-DD] [--summary]"

/**
 * Prints the coverage measure as CSV on stdout: one row per Fundi Bora assessment, or with
 * `--summary` the totals over full assessments as `measure,value` rows. Reads only, in a
 * transaction that is rolled back. On the box:
 *
 *     java -cp /opt/kagua/kagua.jar com.qualityverifier.server.reports.DefectCoverageKt \
 *          --summary
 *
 * with `KAGUA_DB_PASSWORD` in the environment, as for `admin/CreateAdmin.kt`.
 */
fun main(args: Array<String>) {
    val options = CommandArgs(USAGE, args, flags = setOf("--summary"))
    readOnly { connection ->
        val rows = fundiSessions(connection, options.since).map { session ->
            val purpose = FundiIntake.decode(session.intakeAnswers)?.second
            val coverage = coverageOf(session.assistantTexts.asReversed().map(::parseAssistantContent))
            Triple(session, purpose, coverage)
        }
        if ("--summary" in options.flags) {
            println(csvRow(listOf("measure", "value")))
            CoverageSummary.of(rows.map { (_, purpose, coverage) -> purpose?.fullAssessment to coverage })
                .rows().forEach { println(csvRow(it)) }
        } else {
            println(csvRow(COVERAGE_HEADER))
            rows.forEach { (session, purpose, coverage) ->
                println(
                    csvRow(
                        listOf(
                            session.id, isoTime(session.createdAt), session.itemTypeId, purpose?.id,
                            purpose?.fullAssessment, coverage.shape.id, coverage.defects,
                            coverage.findings, coverage.defectsWithoutFinding,
                            coverage.findingsWithoutDefect, coverage.lengthsDiffer,
                        ),
                    ),
                )
            }
        }
    }
}
