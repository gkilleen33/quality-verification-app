package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage

/**
 * The intake answers as one short string, so they can travel in a navigation route and
 * sit in a single database column.
 *
 * Somebody standing in a shop checking four stools answers the same four questions four
 * times, and three of those answers — the language, whether they are buying, what it is
 * for — are the same every time. Carrying them means the second stool asks one question
 * instead of five.
 *
 * The **price is deliberately not carried**. It is the one answer that is about this
 * piece rather than about the afternoon, and a price silently inherited from the last
 * stool would put a wrong number in front of the assistant.
 *
 * Anything unrecognised decodes to null rather than to a partial context, so a route
 * from an older build, or a hand-edited one, falls back to asking properly instead of
 * assuming. There is no version field for the same reason: a shape this codec does not
 * recognise is already handled.
 */
private const val SEPARATOR = "-"

/** Null when the intake was not completed, so there is nothing whole to carry. */
fun encodeIntake(context: AssessmentContext): String? {
    val language = context.language ?: return null
    val ownership = context.ownership ?: return null
    val usage = context.usage ?: return null
    val depth = context.depth ?: return null
    return listOf(language.code, ownership.name, usage.name, depth.name)
        .joinToString(SEPARATOR) { it.lowercase() }
}

fun decodeIntake(encoded: String?): AssessmentContext? {
    val parts = encoded?.trim()?.lowercase()?.split(SEPARATOR) ?: return null
    if (parts.size != PART_COUNT) return null
    return AssessmentContext(
        language = AssessmentLanguage.entries.firstOrNull { it.code == parts[0] } ?: return null,
        ownership = Ownership.entries.byName(parts[1]) ?: return null,
        // Left blank on purpose: see the note above.
        quotedPrice = "",
        usage = Usage.entries.byName(parts[2]) ?: return null,
        depth = AssessmentDepth.entries.byName(parts[3]) ?: return null,
    )
}

private fun <T : Enum<T>> List<T>.byName(value: String): T? =
    firstOrNull { it.name.equals(value, ignoreCase = true) }

/** language, ownership, usage, depth. */
private const val PART_COUNT = 4
