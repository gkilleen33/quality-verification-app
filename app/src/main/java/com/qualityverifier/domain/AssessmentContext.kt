package com.qualityverifier.domain

/**
 * What the app asks before it asks Claude anything.
 *
 * These answers used to be the assistant's first five turns: a request per question, each
 * one a wait on a network round trip, and the whole thing kicked off automatically when
 * the screen opened so that the first thing a customer saw was a spinner. None of it
 * needed a model — they are a language, three multiple-choice questions and a number.
 *
 * Every field is nullable because the intake can be **abandoned part way**. A customer who
 * cannot find their answer among the buttons hands the conversation over instead, and
 * whatever they had already chosen goes with them rather than being thrown away. See
 * [isComplete] and [com.qualityverifier.text.buildIntakeMessage].
 */
data class AssessmentContext(
    val language: AssessmentLanguage? = null,
    val ownership: Ownership? = null,
    /** Blank when not buying, when skipped, or when the intake was abandoned first. */
    val quotedPrice: String = "",
    val usage: Usage? = null,
    val depth: AssessmentDepth? = null,
) {
    /**
     * True when the app answered everything, so the assistant can go straight to a plan.
     * False means it must pick up the questions itself.
     */
    val isComplete: Boolean
        get() = language != null && ownership != null && usage != null && depth != null
}

/**
 * The languages the app can conduct an assessment in.
 *
 * Only two, because these are the two the app has its own wording for — see
 * [com.qualityverifier.text.ReportLabels]. The assistant will happily mirror anything the
 * customer writes later; this is the starting point, not a cage.
 */
enum class AssessmentLanguage(val code: String, val ownName: String) {
    ENGLISH("en", "English"),
    SWAHILI("sw", "Kiswahili"),
}

enum class Ownership { BUYING, ALREADY_OWN }

enum class Usage { DAILY, OCCASIONAL, BUSINESS }

/** How thorough the assessment should be. Recommended answer is [FULL]. */
enum class AssessmentDepth { FULL, RAPID }
