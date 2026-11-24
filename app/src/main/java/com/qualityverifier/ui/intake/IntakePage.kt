package com.qualityverifier.ui.intake

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.IntakeTopic
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage

/**
 * "A few questions first" (v0.7.1, release scope row 58; Nathan, 24 November 2026): every
 * question after the language on one page, one card each, answered in any order by scrolling.
 * Each card has its choices and "Something else: let me explain", which opens a box for the
 * customer's own words. "Start the assessment" is greyed, with "N questions left.", until every
 * card has an answer, a choice or words.
 *
 * Apart from the screen so the cards, the count and the answers sent are tested without Compose.
 *
 * [needsUpholstery] asks "Does this chair have cushioning, or is it bare wood?" (chairs only).
 * [ownershipFixed] is a shop's tablet (row 12): buying is recorded and the card is not shown.
 * The price card shows once the answer is "I'm buying it", and never counts as left: a blank
 * price is "I don't know", as before.
 */
data class IntakePage(
    val language: AssessmentLanguage,
    val needsUpholstery: Boolean,
    val ownershipFixed: Boolean = false,
    val upholstered: Boolean? = null,
    val ownership: Ownership? = null,
    val price: String = "",
    val priceUnknown: Boolean = false,
    val usage: Usage? = null,
    val depth: AssessmentDepth? = null,
    /** The customer's own words on a card, by topic; a blank entry is no answer. */
    val explained: Map<IntakeTopic, String> = emptyMap(),
    /** Cards whose "Something else" box is open. */
    val open: Set<IntakeTopic> = emptySet(),
) {
    /** The cards on the page, in order. */
    val cards: List<IntakeTopic> get() = listOfNotNull(
        IntakeTopic.UPHOLSTERY.takeIf { needsUpholstery },
        IntakeTopic.OWNERSHIP.takeUnless { ownershipFixed },
        IntakeTopic.PRICE.takeIf { ownership == Ownership.BUYING },
        IntakeTopic.USAGE,
        IntakeTopic.DEPTH,
    )

    private fun words(topic: IntakeTopic) = explained[topic]?.trim().orEmpty()

    fun answered(topic: IntakeTopic): Boolean = words(topic).isNotEmpty() || when (topic) {
        IntakeTopic.UPHOLSTERY -> upholstered != null
        IntakeTopic.OWNERSHIP -> ownership != null
        IntakeTopic.PRICE -> true
        IntakeTopic.USAGE -> usage != null
        IntakeTopic.DEPTH -> depth != null
    }

    /** How many cards still need an answer: the line under the greyed Start. */
    val left: Int get() = cards.count { !answered(it) }

    val canStart: Boolean get() = left == 0

    // ---- answering. A choice and the customer's words on the same card replace each other.

    fun chooseUpholstery(value: Boolean) = copy(upholstered = value, explained = explained - IntakeTopic.UPHOLSTERY, open = open - IntakeTopic.UPHOLSTERY)

    fun chooseOwnership(value: Ownership) = copy(
        ownership = value,
        // Owning: no price to ask about.
        price = if (value == Ownership.BUYING) price else "",
        priceUnknown = if (value == Ownership.BUYING) priceUnknown else false,
        explained = explained - IntakeTopic.OWNERSHIP - (if (value == Ownership.BUYING) emptySet() else setOf(IntakeTopic.PRICE)),
        open = open - IntakeTopic.OWNERSHIP,
    )

    fun typePrice(value: String) = copy(price = value, priceUnknown = false, explained = explained - IntakeTopic.PRICE, open = open - IntakeTopic.PRICE)

    fun priceNotKnown() = copy(price = "", priceUnknown = true, explained = explained - IntakeTopic.PRICE, open = open - IntakeTopic.PRICE)

    fun chooseUsage(value: Usage) = copy(usage = value, explained = explained - IntakeTopic.USAGE, open = open - IntakeTopic.USAGE)

    fun chooseDepth(value: AssessmentDepth) = copy(depth = value, explained = explained - IntakeTopic.DEPTH, open = open - IntakeTopic.DEPTH)

    /** "Something else: let me explain": the box opens, and the card's choice is cleared. */
    fun openExplain(topic: IntakeTopic) = clear(topic).copy(open = open + topic)

    fun explain(topic: IntakeTopic, text: String) = clear(topic).copy(open = open + topic, explained = explained + (topic to text))

    private fun clear(topic: IntakeTopic): IntakePage = when (topic) {
        IntakeTopic.UPHOLSTERY -> copy(upholstered = null)
        // Explaining instead of choosing takes the price card away with the choice.
        IntakeTopic.OWNERSHIP -> copy(ownership = if (ownershipFixed) ownership else null, price = "", priceUnknown = false, explained = explained - IntakeTopic.PRICE)
        IntakeTopic.PRICE -> copy(price = "", priceUnknown = false)
        IntakeTopic.USAGE -> copy(usage = null)
        IntakeTopic.DEPTH -> copy(depth = null)
    }

    /**
     * What the first message is written from. A card answered in words carries its words and
     * no choice; the message says each in a fixed sentence (row 58), so the same answers always
     * make the same message.
     */
    fun toContext(): AssessmentContext = AssessmentContext(
        language = language,
        ownership = ownership,
        quotedPrice = if (ownership == Ownership.BUYING) price.trim() else "",
        usage = usage,
        depth = depth,
        explained = cards.mapNotNull { topic -> words(topic).takeIf { it.isNotEmpty() }?.let { topic to it } }.toMap(),
    )

    companion object {
        fun start(language: AssessmentLanguage, needsUpholstery: Boolean, fixedOwnership: Ownership?) = IntakePage(
            language = language,
            needsUpholstery = needsUpholstery,
            ownershipFixed = fixedOwnership != null,
            ownership = fixedOwnership,
        )
    }
}
