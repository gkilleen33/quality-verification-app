package com.qualityverifier.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What went wrong with a piece the maker built, and why.
 *
 * The mockup is emphatic about what this is not: "Diagnosis — not a grade, a cause". A
 * [Verdict] tells a buyer whether to hand over money; this tells a maker which step of
 * their own method produced the defect. Same photographs, same assessment engine,
 * opposite question — which is why it is a separate type rather than a field on Verdict.
 *
 * Every field has a default, for the same reason Verdict's do: a diagnosis missing one
 * field should still render, because the alternative is a fundi standing over a piece
 * with nothing on screen.
 */
@Serializable
data class Diagnosis(
    /**
     * The language the assistant wrote this in, so the app can put its own headings in
     * the same one. Blank means fall back to the device language.
     */
    val language: String = "",
    /** Everything found, worst first, each with its cause and habit. */
    val findings: List<Finding> = emptyList(),
    /**
     * The one question whose answer would change the cause, or blank.
     *
     * At most one, and the prompt says so. The cause of a gapping shoulder depends on
     * whether the cut was made freehand, and no photograph shows that — but a fundi with
     * glue drying will answer one question and abandon three.
     */
    @SerialName("one_check") val oneCheck: String = "",
) {
    /** The worst finding, which the fix plan is for. The prompt orders them worst first. */
    val primary: Finding? get() = findings.firstOrNull()

    /**
     * Recorded whether or not anything was found, and separate from [isRenderable]: a
     * piece with no defects is the outcome the whole app exists to produce, and it still
     * needs storing.
     */
    val defectCount: Int get() = findings.size

    /**
     * A diagnosis with no finding and no question carries nothing to show. An empty
     * findings list on its own is fine — that is a clean piece, and the app says so.
     */
    val isRenderable: Boolean
        get() = findings.isNotEmpty() || oneCheck.isNotBlank()

    /** True while the answer to [oneCheck] is still outstanding. */
    val awaitingCheck: Boolean get() = oneCheck.isNotBlank()
}

/**
 * One defect and what caused it.
 *
 * Every finding carries its cause and habit (issue #41). They used to be given for the
 * worst one only, on the theory that six habits to change means none get changed — but
 * that decided for the maker what to work on. The fix plan still covers one finding at a
 * time; the cause of every one is theirs to read.
 *
 * Older diagnoses have the cause fields on the first finding only, so all three stay
 * optional and each is drawn only when present.
 */
@Serializable
data class Finding(
    val title: String = "",
    val area: String = "",
    @SerialName("severity") val severityId: String = "",
    /**
     * Which skill this belongs to, so it can be tracked across months. Matches
     * [SkillDimension]. Blank on older diagnoses, which gave it for the first finding only.
     */
    val dimension: String = "",
    /** The observable fact. What a photograph shows, in plain words. */
    @SerialName("what_happened") val whatHappened: String = "",
    /**
     * The step in the making where it went wrong.
     *
     * Not a quality judgement — "poor workmanship" is exactly what this field exists to
     * replace. Which step, and what about it.
     */
    @SerialName("manufacturing_cause") val manufacturingCause: String = "",
    /**
     * What will produce this defect again on the next piece unless it changes.
     *
     * About the method, never about the person. The prompt forbids describing a maker as
     * careless or unskilled, and this is the field where that would otherwise creep in.
     */
    @SerialName("root_habit") val rootHabit: String = "",
    /** The measurement, where the photographs supported one. Null together with its unit. */
    @SerialName("measured_value") val measuredValue: Double? = null,
    @SerialName("measured_unit") val measuredUnit: String = "",
) {
    val severity: Severity get() = Severity.fromId(severityId)

    val skillDimension: SkillDimension? get() = SkillDimension.fromId(dimension)

    /**
     * The measurement as something storable, or null.
     *
     * Both or neither, matching the CHECK on `fundi_observations`: a value without its
     * unit is worse than no value, because it looks precise.
     */
    val observation: SkillObservation?
        get() {
            val dimension = skillDimension ?: return null
            val unit = MeasurementUnit.fromId(measuredUnit)
            val value = measuredValue
            val paired = if (value != null && unit != null) value to unit else null
            return SkillObservation(
                dimension = dimension,
                value = paired?.first,
                unit = paired?.second,
                // No level from the model: mapping millimetres to "L2" is a rubric
                // decision, and the argument for storing measurements is that it stays
                // one. Filled in by whatever computes the skill file.
                level = null,
            ).takeIf { it.isMeaningful }
        }

    /** Whether this carries any of the cause chain. Older diagnoses: the first finding only. */
    val isCoached: Boolean
        get() = whatHappened.isNotBlank() ||
            manufacturingCause.isNotBlank() ||
            rootHabit.isNotBlank()
}
