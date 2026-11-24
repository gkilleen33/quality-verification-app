package com.qualityverifier.domain

/**
 * Whether a studio entry's number is the account's own (row 52; Nathan, 23 November 2026).
 *
 * No link forms with oneself (V31), so an entry on one's own number could never say
 * "Linked", and a piece labelled for it had nobody to make its final assessment. The studio
 * form refuses it, and the server refuses the same write; this is the phone's half.
 *
 * Kenyan numbers in every form a person or a contacts app writes them: +254712345678,
 * 254712345678, 0712345678 and 712345678, with spaces or dashes. Since v0.7.1 (row 47) an
 * account may be on a number from another country, so any number written with its + (or
 * 00) and country code is compared too, as the E.164 the server stores. A number this
 * cannot read is never called the account's own; it is let through to the server, which
 * normalises it the way sign-in does and refuses it there if it is.
 */
object OwnNumber {

    /** The nine digits after +254, or null for a number that is not Kenyan in a form above. */
    fun national(raw: String?): String? {
        val digits = raw?.filter { it in '0'..'9' } ?: return null
        val national = when {
            digits.length == 12 && digits.startsWith("254") -> digits.substring(3)
            digits.length == 10 && digits.startsWith("0") -> digits.substring(1)
            digits.length == 9 -> digits
            // +2540712… : the prefilled +254 followed by the number as people say it.
            digits.length == 13 && digits.startsWith("2540") -> digits.substring(4)
            else -> return null
        }
        return national.takeIf { it.first() != '0' }
    }

    /**
     * [raw] as the E.164 the server would store, or null when it is in no form read here:
     * a Kenyan number in any form [national] reads, or any number with its + or 00 and a
     * country code.
     */
    fun e164(raw: String?): String? {
        val cleaned = raw?.dropWhile { it != '+' && it !in '0'..'9' } ?: return null
        val digits = cleaned.filter { it in '0'..'9' }
        val withCode = when {
            cleaned.startsWith("+") -> digits
            digits.startsWith("00") -> digits.removePrefix("00")
            else -> null
        }
        if (withCode != null && !withCode.startsWith(PhoneEntry.KENYA)) {
            return "+$withCode".takeIf { E164.matches(it) }
        }
        return national(raw)?.let { "+${PhoneEntry.KENYA}$it" }
    }

    /** True when [studioNumber] and [ownNumber] are the same number. */
    fun isOwn(studioNumber: String?, ownNumber: String?): Boolean {
        val studio = e164(studioNumber) ?: return false
        return studio == e164(ownNumber)
    }

    /** V21's rule for a stored number. */
    private val E164 = Regex("""^\+[1-9][0-9]{7,14}$""")
}
