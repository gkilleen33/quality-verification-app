package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.IntakeTopic
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage

/**
 * The customer's opening turn, written from whatever the app collected.
 *
 * A plain-language message rather than a new field on
 * [com.qualityverifier.data.chat.ChatService]: the context is something the customer is
 * telling the assistant, it belongs in their own conversation where they can see it, and
 * keeping it out of the system prompt means every language shares one cached prefix
 * instead of splitting it.
 *
 * Since v0.7.1 (release scope row 58) every question is on one page and each must be answered
 * before the assessment starts, by a choice or in the customer's own words. A question
 * answered in words is told in one fixed sentence per question ("About the price: …") at the
 * place its choice would have been, so the same answers always make the same turn. That
 * replaces the line that used to hand the rest of the questions to the assistant ("I could
 * not answer the rest with the buttons, so please ask me yourself."): nothing is left
 * unanswered now.
 *
 * Any question that is neither chosen nor explained, such as a carried intake's, is simply
 * not mentioned.
 */
fun buildIntakeMessage(context: AssessmentContext, labels: ReportLabels): String {
    val parts = mutableListOf<String>()
    fun explained(topic: IntakeTopic) = context.explained[topic]?.trim()?.takeIf { it.isNotEmpty() }

    // The cushioning decides the protocol, not the turn; only the customer's own words about
    // it are told.
    explained(IntakeTopic.UPHOLSTERY)?.let { parts += labels.intakeSaysAbout(IntakeTopic.UPHOLSTERY, it) }

    val ownership = context.ownership
    if (ownership != null) {
        parts += when (ownership) {
            Ownership.BUYING -> labels.intakeSaysBuying
            Ownership.ALREADY_OWN -> labels.intakeSaysAlreadyOwn
        }
        if (ownership == Ownership.BUYING) {
            parts += explained(IntakeTopic.PRICE)?.let { labels.intakeSaysAbout(IntakeTopic.PRICE, it) }
                ?: if (context.quotedPrice.isBlank()) {
                    labels.intakeSaysPriceUnknown
                } else {
                    labels.intakeSaysPrice(context.quotedPrice.trim())
                }
        }
    } else {
        explained(IntakeTopic.OWNERSHIP)?.let { parts += labels.intakeSaysAbout(IntakeTopic.OWNERSHIP, it) }
    }

    val usage = context.usage
    if (usage != null) {
        parts += when (usage) {
            Usage.DAILY -> labels.intakeSaysDaily
            Usage.OCCASIONAL -> labels.intakeSaysOccasional
            Usage.BUSINESS -> labels.intakeSaysBusiness
        }
    } else {
        explained(IntakeTopic.USAGE)?.let { parts += labels.intakeSaysAbout(IntakeTopic.USAGE, it) }
    }

    val depth = context.depth
    if (depth != null) {
        parts += when (depth) {
            AssessmentDepth.FULL -> labels.intakeSaysFull
            AssessmentDepth.RAPID -> labels.intakeSaysRapid
        }
    } else {
        explained(IntakeTopic.DEPTH)?.let { parts += labels.intakeSaysAbout(IntakeTopic.DEPTH, it) }
    }

    // Stated rather than inferred. Left to guesswork the assistant picked a language from
    // the item name and then would not change it when written to in the other one.
    context.language?.let { parts += labels.intakeSaysUseLanguage }

    return parts.joinToString(" ")
}
