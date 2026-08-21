package com.qualityverifier.text

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage

/**
 * The customer's opening turn, written from what the app collected.
 *
 * A plain-language message rather than a new field on
 * [com.qualityverifier.data.chat.ChatService]: the context is something the customer is
 * telling the assistant, it belongs in their own conversation where they can see it, and
 * keeping it out of the system prompt means every language shares one cached prefix
 * instead of splitting it.
 *
 * It is written in the language they chose, so what appears in their chat reads as
 * something they said.
 */
fun buildIntakeMessage(context: AssessmentContext, labels: ReportLabels): String =
    buildString {
        append(
            when (context.ownership) {
                Ownership.BUYING -> labels.intakeSaysBuying
                Ownership.ALREADY_OWN -> labels.intakeSaysAlreadyOwn
            },
        )
        if (context.ownership == Ownership.BUYING) {
            append(" ")
            append(
                if (context.quotedPrice.isBlank()) {
                    labels.intakeSaysPriceUnknown
                } else {
                    labels.intakeSaysPrice(context.quotedPrice.trim())
                },
            )
        }
        append(" ")
        append(
            when (context.usage) {
                Usage.DAILY -> labels.intakeSaysDaily
                Usage.OCCASIONAL -> labels.intakeSaysOccasional
                Usage.BUSINESS -> labels.intakeSaysBusiness
            },
        )
        // Stated rather than inferred. Left to guesswork the assistant picked a language
        // from the item name and then would not change it when written to in the other one.
        append(" ")
        append(labels.intakeSaysUseLanguage)
    }
