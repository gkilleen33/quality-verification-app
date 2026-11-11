package com.qualityverifier.domain

/**
 * One problem with a piece, whichever list of the reply it was written in.
 *
 * Fundi Bora's replies have written each problem twice: as a [Finding] in the diagnosis
 * (cause, dimension, habit) and as a [Defect] in the verdict (what I see, what it means,
 * what to do), linked only by title. The prompt is moving to one list, the findings with
 * the three consequence fields on them, but replies stored in the old shape stay in it.
 * Anything that shows or counts problems takes this rather than either list, so the two
 * shapes are told apart in one place: [com.qualityverifier.text.problemsOf].
 *
 * Its own type rather than [Defect] reused, because a defect has no dimension, and the
 * dimension is what ties a problem to a skill. In the old shape it is borrowed from the
 * finding with the same title; in the new one it is on the finding itself.
 */
data class Problem(
    val title: String,
    val area: String,
    val severityId: String,
    /** A [SkillDimension] id, or blank when the reply gave none or no finding matched. */
    val dimension: String,
    val whatISee: String,
    val whatItMeans: String,
    val whatToDo: String,
) {
    val severity: Severity get() = Severity.fromId(severityId)

    val skillDimension: SkillDimension? get() = SkillDimension.fromId(dimension)
}
