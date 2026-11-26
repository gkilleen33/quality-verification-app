package com.qualityverifier.domain

/**
 * What the app asks before it asks Claude anything.
 *
 * These answers used to be the assistant's first five turns: a request per question, each
 * one a wait on a network round trip, and the whole thing kicked off automatically when
 * the screen opened so that the first thing a customer saw was a spinner. None of it
 * needed a model — they are a language, three multiple-choice questions and a number.
 *
 * Every choice is nullable because a question can be answered in the customer's own words
 * instead ([explained], v0.7.1, row 58), and because answers from before v0.7.1 could be
 * abandoned part way. See [isComplete] and [com.qualityverifier.text.buildIntakeMessage].
 */
data class AssessmentContext(
    val language: AssessmentLanguage? = null,
    val ownership: Ownership? = null,
    /** Blank when not buying, when skipped, or when the intake was abandoned first. */
    val quotedPrice: String = "",
    val usage: Usage? = null,
    val depth: AssessmentDepth? = null,
    /**
     * Questions answered in the customer's own words, by "Something else: let me explain" on
     * their card (v0.7.1, release scope row 58), in place of a choice. A context with any is
     * not [isComplete] and has no intake code ([com.qualityverifier.text.encodeIntake]), so it
     * is not carried into the next piece, even from a report reopened later, and the next
     * check asks every question. Its answers are in the conversation's first turn.
     */
    val explained: Map<IntakeTopic, String> = emptyMap(),
) {
    /**
     * True when the app answered everything, so the assistant can go straight to a plan.
     * False means it must pick up the questions itself.
     */
    val isComplete: Boolean
        // An answer in words (row 58), even beside a full set of choices, is not carried into
        // the next piece: the words were about this one, and a cushioning described in words
        // never settled which chair protocol applies.
        get() = language != null && ownership != null && usage != null && depth != null && explained.isEmpty()
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

/**
 * The questions on Kagua's page of questions after the language (v0.7.1, release scope row
 * 58), each of which can be answered in the customer's own words. In the order the page asks
 * them and the first turn tells them.
 */
enum class IntakeTopic { UPHOLSTERY, OWNERSHIP, PRICE, USAGE, DEPTH }

enum class Usage { DAILY, OCCASIONAL, BUSINESS }

/** How thorough the assessment should be. Recommended answer is [FULL]. */
enum class AssessmentDepth { FULL, RAPID }
