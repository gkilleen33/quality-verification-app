package com.qualityverifier.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The structured assessment the app renders as cards instead of prose.
 *
 * This arrives inside the assistant's own message text, in a fenced ```qv-verdict```
 * block, rather than through a separate request or a tool call. That choice keeps
 * [com.qualityverifier.data.chat.ChatService] returning plain text, so the Phase 2
 * server swap stays a one-file change, and it keeps the whole assessment in a single
 * cached conversation prefix instead of paying for a second system prompt.
 *
 * Every field has a default: a verdict missing a field should still render, because the
 * alternative is a customer standing in a shop with nothing on screen. Unknown fields
 * are ignored for the same reason.
 */
@Serializable
data class Verdict(
    @SerialName("verdict") val levelId: String = "",
    /**
     * The language the assistant wrote this verdict in, so the app can put its own
     * headings in the same language. Blank when the model left it out, which the UI
     * treats as "fall back to the device language".
     */
    val language: String = "",
    val headline: String = "",
    val summary: String = "",
    val defects: List<Defect> = emptyList(),
    val unverified: List<String> = emptyList(),
    val questions: List<String> = emptyList(),
) {
    val level: VerdictLevel get() = VerdictLevel.fromId(levelId)

    /** A verdict with no headline and no defects carries nothing worth showing. */
    val isRenderable: Boolean
        get() = headline.isNotBlank() || summary.isNotBlank() || defects.isNotEmpty()
}

@Serializable
data class Defect(
    val title: String = "",
    val area: String = "",
    @SerialName("severity") val severityId: String = "",
    @SerialName("what_i_see") val whatISee: String = "",
    @SerialName("what_it_means") val whatItMeans: String = "",
    @SerialName("what_to_do") val whatToDo: String = "",
    @SerialName("ask_seller") val askSeller: String? = null,
) {
    val severity: Severity get() = Severity.fromId(severityId)
}

/**
 * The three headline levels. Deliberately not a numeric score — see the master prompt.
 *
 * Carries no display text: what a level is called depends on the language of the
 * assessment, so the words live in [com.qualityverifier.text.ReportLabels].
 */
enum class VerdictLevel(val id: String) {
    SOUND("sound"),
    FAIR("fair"),
    SERIOUS("serious_concerns"),

    /** The model returned something we do not recognise. Render it without a badge. */
    UNKNOWN("");

    companion object {
        fun fromId(id: String): VerdictLevel =
            entries.firstOrNull { it.id == id.trim().lowercase() } ?: UNKNOWN
    }
}

enum class Severity(val id: String) {
    SERIOUS("serious"),
    MODERATE("moderate"),
    MINOR("minor"),
    COSMETIC("cosmetic"),
    UNKNOWN("");

    companion object {
        fun fromId(id: String): Severity =
            entries.firstOrNull { it.id == id.trim().lowercase() } ?: UNKNOWN
    }
}
