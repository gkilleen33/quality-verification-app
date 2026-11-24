package com.qualityverifier.domain

/**
 * Whether a studio entry's number is the account's own (row 52; Nathan, 23 November 2026).
 *
 * No link forms with oneself (V31), so an entry on one's own number could never say
 * "Linked", and a piece labelled for it had nobody to make its final assessment. The studio
 * form refuses it, and the server refuses the same write; this is the phone's half.
 *
 * Kenyan numbers only, in every form a person or a contacts app writes them: +254712345678,
 * 254712345678, 0712345678 and 712345678, with spaces or dashes. Any other number is never
 * called the account's own, so a number this cannot read is let through to the server, which
 * normalises it the way sign-in does and refuses it there if it is.
 */
object OwnNumber {

    /** The nine digits after +254, or null for a number that is not Kenyan in a form above. */
    fun national(raw: String?): String? {
        val digits = raw?.filter { it.isDigit() } ?: return null
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

    /** True when [studioNumber] and [ownNumber] are the same Kenyan number. */
    fun isOwn(studioNumber: String?, ownNumber: String?): Boolean {
        val studio = national(studioNumber) ?: return false
        return studio == national(ownNumber)
    }
}
