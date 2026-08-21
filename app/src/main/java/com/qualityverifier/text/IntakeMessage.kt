package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage

/**
 * The customer's opening turn, written from whatever the app managed to collect.
 *
 * A plain-language message rather than a new field on
 * [com.qualityverifier.data.chat.ChatService]: the context is something the customer is
 * telling the assistant, it belongs in their own conversation where they can see it, and
 * keeping it out of the system prompt means every language shares one cached prefix
 * instead of splitting it.
 *
 * When the intake was abandoned part way, everything already chosen is still sent, plus a
 * line asking the assistant to take over the questions. Partial context is worth having:
 * a customer who gave up at the usage question has still told us they are buying, and
 * making them repeat that would be the second time the app failed them.
 */
fun buildIntakeMessage(context: AssessmentContext, labels: ReportLabels): String {
    val parts = mutableListOf<String>()

    context.ownership?.let { ownership ->
        parts += when (ownership) {
            Ownership.BUYING -> labels.intakeSaysBuying
            Ownership.ALREADY_OWN -> labels.intakeSaysAlreadyOwn
        }
        if (ownership == Ownership.BUYING) {
            parts += if (context.quotedPrice.isBlank()) {
                labels.intakeSaysPriceUnknown
            } else {
                labels.intakeSaysPrice(context.quotedPrice.trim())
            }
        }
    }

    context.usage?.let { usage ->
        parts += when (usage) {
            Usage.DAILY -> labels.intakeSaysDaily
            Usage.OCCASIONAL -> labels.intakeSaysOccasional
            Usage.BUSINESS -> labels.intakeSaysBusiness
        }
    }

    context.depth?.let { depth ->
        parts += when (depth) {
            AssessmentDepth.FULL -> labels.intakeSaysFull
            AssessmentDepth.RAPID -> labels.intakeSaysRapid
        }
    }

    // Stated rather than inferred. Left to guesswork the assistant picked a language from
    // the item name and then would not change it when written to in the other one.
    context.language?.let { parts += labels.intakeSaysUseLanguage }

    // The handover. Last, so it reads as the request it is rather than as an aside.
    if (!context.isComplete) parts += labels.intakeSaysTakeOver

    return parts.joinToString(" ")
}
