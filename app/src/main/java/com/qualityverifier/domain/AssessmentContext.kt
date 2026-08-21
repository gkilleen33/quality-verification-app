package com.qualityverifier.domain

/**
 * What the app asks before it asks Claude anything.
 *
 * These three or four answers used to be the assistant's first four turns: a request per
 * question, each one a wait on a network round trip, and the whole thing kicked off
 * automatically when the screen opened so that the first thing a customer saw was a
 * spinner. None of it needed a model — they are a language, two multiple-choice questions
 * and a number.
 *
 * Collecting them on the device means the chat opens instantly and the first request
 * carries the context with it.
 */
data class AssessmentContext(
    val language: AssessmentLanguage,
    val ownership: Ownership,
    /** Blank when not buying, or when they did not want to say. */
    val quotedPrice: String = "",
    val usage: Usage,
)

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
