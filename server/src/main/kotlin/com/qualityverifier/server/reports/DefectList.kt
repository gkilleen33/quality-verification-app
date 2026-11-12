package com.qualityverifier.server.reports

import com.qualityverifier.domain.Diagnosis
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.Problem
import com.qualityverifier.domain.SkillDimension
import com.qualityverifier.domain.Verdict
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.text.AssistantContent
import com.qualityverifier.text.fundiDefectCount
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.problemsOf
import com.qualityverifier.text.writesConsequences

/**
 * The problems of one assessment, read from its stored replies.
 *
 * Derived on every read and never stored, so a corrected verdict, a purge, or a parser fix
 * applies at once with no backfill. Every server reader of an assessment's problems goes
 * through this (the data API now, the PDF reports later), so they cannot disagree.
 *
 * The session's newest verdict and newest diagnosis decide, read exactly as
 * [fundiDefectCount] reads them, so for a session with a verdict the list has as many
 * problems as `sessions.verdict_defect_count`. A follow-up often resends one block and not
 * the other, which is why it is the newest of each rather than the newest reply.
 *
 * Every purpose, not only full assessments: a diagnosis, a work-in-progress check, or a
 * customer return has no verdict, and its findings are its problems ([problemsOf]).
 * A Kagua session has no diagnosis and reads as its verdict's defects.
 *
 * A verdict with no defects lists the findings of a diagnosis in the same reply or a newer
 * one, cosmetic findings included (Nathan, 11 November 2026), and nothing for an older
 * diagnosis, which that verdict has cleared. That is [fundiDefectCount]'s fourth rule.
 */
data class DefectList(
    /** Why the maker opened the assessment, from `sessions.intake_answers`; null for Kagua. */
    val purpose: FundiPurpose?,
    /** In the order the reply gave them: worst first. Empty when [unknown]. */
    val problems: List<ListedProblem>,
    /**
     * True when the problems cannot be known: the newest diagnosis block would not decode
     * and no verdict defects stand in for it. Not the same as an empty list, which is a
     * reply that found nothing; the defect count is null in the same case.
     */
    val unknown: Boolean,
) {
    companion object {
        /** From the session's assistant replies as stored, oldest first (`messages.ordinal`). */
        fun fromTexts(assistantTextsOldestFirst: List<String>, purpose: FundiPurpose? = null): DefectList =
            fromReplies(assistantTextsOldestFirst.asReversed().map(::parseAssistantContent), purpose)

        /** From the session's assistant replies, parsed, newest first. */
        fun fromReplies(repliesNewestFirst: List<AssistantContent>, purpose: FundiPurpose? = null): DefectList {
            val newest = NewestBlocks.of(repliesNewestFirst)
            val verdict = newest.verdict
            val diagnosis = newest.diagnosis
            val problems = when {
                // fundiDefectCount's first rule: a newest diagnosis in the one-list shape gives
                // the problems even beside an older verdict that still lists defects, which a
                // session straddling the prompt change can have. problemsOf alone would take
                // the defects.
                diagnosis != null && diagnosis.writesConsequences -> problemsOf(null, diagnosis)
                // No verdict, verdict defects, or a diagnosis no older than the verdict:
                // problemsOf decides, as the count's other rules do.
                verdict == null || verdict.defects.isNotEmpty() || newest.diagnosisNotOlderThanVerdict ->
                    problemsOf(verdict, diagnosis)
                // A verdict with no defects after the newest diagnosis, as today's prompt writes
                // for "sound, all fixed": that diagnosis's findings are cleared, and listing
                // them would disagree with the count of zero.
                else -> emptyList()
            }
            val unknown = newest.diagnosisUnreadable && problems.isEmpty()
            return DefectList(
                purpose = purpose,
                problems = if (unknown) emptyList() else problems.map { ListedProblem.of(it) },
                unknown = unknown,
            )
        }
    }
}

/**
 * One problem as the data API and the pilot measures report it.
 *
 * The closed vocabularies (area, severity, dimension) are trimmed and lowercased, since a
 * case difference there is noise; the title is as the model wrote it, because the title is
 * what links a verdict defect to its finding and a person reads it.
 */
data class ListedProblem(
    val title: String,
    /** One of the prompt's seven areas, or blank. */
    val area: String,
    /** A [com.qualityverifier.domain.Severity] id, or blank. */
    val severity: String,
    /** A [SkillDimension] id, or blank when the reply gave none or no finding matched. */
    val dimension: String,
    /** The part of the piece this problem is put down to, or null for none ([DefaultParts]). */
    val defaultPart: WorkPart?,
    /**
     * The problem's consequence as the reply wrote it, for the PDF reports. Blank where the
     * reply gave none. The data API does not carry these; the wording stays in the messages.
     */
    val whatISee: String = "",
    val whatItMeans: String = "",
    val whatToDo: String = "",
) {
    companion object {
        fun of(problem: Problem): ListedProblem = ListedProblem(
            title = problem.title.trim(),
            area = problem.area.trim().lowercase(),
            severity = problem.severityId.trim().lowercase(),
            dimension = problem.dimension.trim().lowercase(),
            defaultPart = DefaultParts.of(area = problem.area, dimension = problem.dimension),
            whatISee = problem.whatISee.trim(),
            whatItMeans = problem.whatItMeans.trim(),
            whatToDo = problem.whatToDo.trim(),
        )
    }
}

/**
 * The part of a piece a problem is put down to, from the labels Claude already gives it.
 *
 * No step for the manager to change it (decided 10 November 2026: it adds friction), so a
 * wrong default reaches the wrong worker. That is why the table leaves a problem unplaced
 * whenever its labels do not say whose work it was: an unplaced problem cannot reach the
 * wrong worker by accident; a misplaced one can.
 *
 * One table, read top to bottom; the first row whose label matches decides, and a row with
 * no part decides "none" just as firmly. So the upholstery area comes first, then every
 * dimension, then the areas a dimension did not settle. No row matching is also "none".
 * The dimension is the finer label (which skill caused it), so it is read before the area
 * (what kind of problem it is); in today's replies a defect takes it from the finding with
 * the same title ([problemsOf]), and a defect with no such finding falls to its area.
 */
object DefaultParts {

    enum class Label { AREA, DIMENSION }

    data class Rule(val label: Label, val value: String, val part: WorkPart?)

    val RULES: List<Rule> = listOf(
        // Upholstery wins whatever the dimension (Nathan, 10 November 2026): the dimension
        // list has no upholstery skill, so a sagging seat would otherwise go to the finisher
        // or the frame maker.
        Rule(Label.AREA, "upholstery", WorkPart.UPHOLSTERY),
        // A frame out of square is the frame maker's.
        Rule(Label.DIMENSION, SkillDimension.FRAME_SQUARENESS.id, WorkPart.FRAME),
        // A joint that does not fit is the joiner's.
        Rule(Label.DIMENSION, SkillDimension.JOINT_FIT.id, WorkPart.JOINERY),
        // Finish quality is the finisher's.
        Rule(Label.DIMENSION, SkillDimension.FINISHING.id, WorkPart.FINISHING),
        // Sanding, filling, and surface preparation are the finisher's too.
        Rule(Label.DIMENSION, SkillDimension.SURFACE.id, WorkPart.FINISHING),
        // Hinges, handles, and fittings go on at assembly.
        Rule(Label.DIMENSION, SkillDimension.HARDWARE.id, WorkPart.ASSEMBLY),
        // Timber choice is usually the manager's purchase. Stops here: a material problem
        // filed under the surface area is still not the finisher's.
        Rule(Label.DIMENSION, SkillDimension.MATERIAL.id, null),
        // No dimension: a surface problem is the finisher's.
        Rule(Label.AREA, "surface", WorkPart.FINISHING),
        // No dimension: a hardware problem went on at assembly.
        Rule(Label.AREA, "hardware", WorkPart.ASSEMBLY),
        // No dimension: a frame member or a joint, and the label does not say which.
        Rule(Label.AREA, "structural", null),
        // No dimension: a wobble can come from the frame, the joints, or assembly.
        Rule(Label.AREA, "level", null),
        // No dimension: timber choice, as above.
        Rule(Label.AREA, "material", null),
        // No dimension: nothing says whose work it was.
        Rule(Label.AREA, "other", null),
    )

    /** Both labels are trimmed and lowercased; an unknown or blank value matches no row. */
    fun of(area: String, dimension: String): WorkPart? {
        val areaKey = area.trim().lowercase()
        val dimensionKey = dimension.trim().lowercase()
        return RULES.firstOrNull { rule ->
            when (rule.label) {
                Label.AREA -> rule.value == areaKey
                Label.DIMENSION -> rule.value == dimensionKey
            }
        }?.part
    }
}

/**
 * A session's newest verdict and newest diagnosis, as [fundiDefectCount] finds them.
 *
 * The newest diagnosis is the newest reply that sent a diagnosis block of any kind: one
 * that found nothing, or one that would not decode, still replaces an older one, since
 * reading past it would bring back problems the model has since cleared or never wrote.
 */
internal class NewestBlocks(
    val verdict: Verdict?,
    private val diagnosisReply: AssistantContent?,
    /**
     * Whether the newest diagnosis came in the same reply as the newest verdict, or in a
     * newer one. False when either is missing. [fundiDefectCount] counts the findings of a
     * diagnosis beside a verdict with no defects only in that case.
     */
    val diagnosisNotOlderThanVerdict: Boolean,
) {
    /** Null when there is none, when it found nothing, or when it would not decode. */
    val diagnosis: Diagnosis? get() = diagnosisReply?.diagnosis

    val sentDiagnosis: Boolean get() = diagnosisReply != null

    val diagnosisUnreadable: Boolean get() = diagnosisReply?.diagnosisUnreadable == true

    companion object {
        fun of(repliesNewestFirst: List<AssistantContent>): NewestBlocks {
            // Indices into the list, newest first: a smaller index is a newer reply.
            val verdictIndex = repliesNewestFirst.indexOfFirst { it.verdict != null }
            val diagnosisIndex = repliesNewestFirst.indexOfFirst { it.sentDiagnosis }
            return NewestBlocks(
                verdict = repliesNewestFirst.getOrNull(verdictIndex)?.verdict,
                diagnosisReply = repliesNewestFirst.getOrNull(diagnosisIndex),
                diagnosisNotOlderThanVerdict = verdictIndex >= 0 && diagnosisIndex >= 0 && diagnosisIndex <= verdictIndex,
            )
        }
    }
}
