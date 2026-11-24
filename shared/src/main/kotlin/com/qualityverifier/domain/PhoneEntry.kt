package com.qualityverifier.domain

import com.qualityverifier.domain.KenyanPhone.Problem

/**
 * What the phone field holds: a country code, then the digits after it (v0.7.1, row 47).
 *
 * Kenya's code, 254, is the default and keeps every rule [KenyanPhone] has: nine digits,
 * the first 7, 1 or 2, the trunk 0 dropped, pasted forms read. Testers outside Kenya could
 * not sign up, so the person may now tap the code and type another (Nathan, 24 November
 * 2026). After any other code the digits are whatever makes a valid E.164 number, which is
 * the database's own rule (V21, V30, V31: `^\+[1-9][0-9]{7,14}$`): at least [MIN_DIGITS]
 * of them, and code and digits together from [MIN_E164_DIGITS] to [MAX_E164_DIGITS].
 *
 * The server already takes any E.164 number sent with its +, and no SMS goes to any number,
 * so nothing outside the phone changes.
 *
 * One copy for every app: Kagua, Fundi Bora and PesaLog draw the field from `:design`, and
 * this decides what it keeps and what it sends. M-Pesa numbers stay Kenyan only
 * ([KenyanPhone], which the server's CounterpartyCoder uses): M-Pesa numbers are Kenyan.
 */
data class PhoneEntry(
    /** The country code's digits, without the +: "254", "256", "44". */
    val code: String = KENYA,
    /** The digits after the code. */
    val digits: String = "",
) {
    val kenyan: Boolean get() = code == KENYA

    /** Why this cannot be sent yet, or null when it is a complete number. */
    val problem: Problem?
        get() = when {
            kenyan -> KenyanPhone.problem(digits)
            !isCountryCode(code) -> Problem.BAD_CODE
            digits.startsWith("0") -> Problem.LEADING_ZERO
            digits.length < MIN_DIGITS || code.length + digits.length < MIN_E164_DIGITS -> Problem.TOO_SHORT
            else -> null
        }

    /** The number as the server takes it, "+256772123456", or null while [problem] is set. */
    fun toE164(): String? = when {
        kenyan -> KenyanPhone.toE164(digits)
        problem == null && digits.all { it in '0'..'9' } && code.length + digits.length <= MAX_E164_DIGITS ->
            "+$code$digits"
        else -> null
    }

    /**
     * What the field holds after an edit. [refused] is set when the edit was rejected and
     * [entry] is the value from before it, so the screen can say why nothing happened.
     */
    data class Edit(val entry: PhoneEntry, val refused: Problem? = null)

    companion object {
        const val KENYA = "254"

        /** The fewest digits after a code other than Kenya's (row 47). */
        const val MIN_DIGITS = 4

        /** E.164's bounds on code and digits together, as V21's CHECK has them. */
        const val MIN_E164_DIGITS = 8
        const val MAX_E164_DIGITS = 15

        /**
         * The two-digit country codes. Country codes are a prefix code: no code is the start
         * of another. 1 and 7 are the one-digit codes, these are the two-digit ones, and every
         * other code is three digits, so these settle where a pasted number's code ends.
         */
        private val TWO_DIGIT_CODES = setOf(
            "20", "27",
            "30", "31", "32", "33", "34", "36", "39",
            "40", "41", "43", "44", "45", "46", "47", "48", "49",
            "51", "52", "53", "54", "55", "56", "57", "58",
            "60", "61", "62", "63", "64", "65", "66",
            "81", "82", "84", "86",
            "90", "91", "92", "93", "94", "95", "98",
        )

        /** How long the country code at the start of [digits] is, or null if it cannot be one. */
        fun codeLength(digits: String): Int? {
            val first = digits.firstOrNull() ?: return null
            return when {
                first !in '1'..'9' -> null
                first == '1' || first == '7' -> 1
                digits.length >= 2 && digits.take(2) in TWO_DIGIT_CODES -> 2
                digits.length >= 3 && digits.take(3).all { it in '0'..'9' } -> 3
                else -> null
            }
        }

        /** True when [code] is a whole country code: "254", "44", "1"; not "25" or "2541". */
        fun isCountryCode(code: String): Boolean =
            code.isNotEmpty() && code.all { it in '0'..'9' } && codeLength(code) == code.length

        /**
         * The entry a stored number fills the field with: "+256772123456" is 256 and
         * 772123456. An empty field for null, for a number without its +, and for one whose
         * code cannot be read, so the field never shows a guess.
         */
        fun fromE164(number: String?): PhoneEntry {
            val digits = number?.takeIf { it.startsWith("+") }?.drop(1) ?: return PhoneEntry()
            if (!digits.all { it in '0'..'9' }) return PhoneEntry()
            val length = codeLength(digits) ?: return PhoneEntry()
            return PhoneEntry(digits.take(length), digits.drop(length))
        }

        /**
         * Applies one change the person made to the digits, typed or pasted.
         *
         * With Kenya's code the edit is [KenyanPhone.edit]'s, unchanged, except that a pasted
         * international number from another country, which it refuses as not Kenyan, fills
         * the code and the digits instead.
         *
         * With another code: only ASCII digits are kept; a whole number pasted with + or 00
         * fills the code and the digits (Kenya's rules again if its code is 254); more digits
         * than E.164 allows is refused without a message, as a full Kenyan field is; and a
         * first digit 0, the trunk prefix a number after its country code never has, is
         * refused unless it was left there by a deletion.
         */
        fun edit(previous: PhoneEntry, proposed: String): Edit {
            val raw = proposed.dropWhile { it != '+' && it !in '0'..'9' }
            val digits = raw.filter { it in '0'..'9' }

            // A whole number with its code is a paste: a typed + adds one character, and a
            // + typed in front of the digits already there is not a new country code.
            val inserted = proposed.length - previous.digits.length
            val international = inserted > 1 && (raw.startsWith("+") || digits.startsWith("00")) &&
                digits.removePrefix("00").length >= MIN_E164_DIGITS
            if (international) {
                val whole = pasted(proposed) ?: return Edit(previous, Problem.BAD_CODE)
                if (!whole.kenyan) {
                    // Too long for E.164: refused without a message, as a full field is.
                    return if (whole.code.length + whole.digits.length > MAX_E164_DIGITS) Edit(previous) else Edit(whole)
                }
                // Kenya's code: read by Kenya's rules, as the field always read a +254 paste.
                val kenyan = KenyanPhone.edit(if (previous.kenyan) previous.digits else "", proposed)
                return if (kenyan.refused == null) Edit(PhoneEntry(KENYA, kenyan.digits)) else Edit(previous, kenyan.refused)
            }

            if (previous.kenyan) {
                val kenyan = KenyanPhone.edit(previous.digits, proposed)
                return Edit(previous.copy(digits = kenyan.digits), kenyan.refused)
            }

            if (previous.code.length + digits.length > MAX_E164_DIGITS) return Edit(previous)
            val zeroFirst = digits.startsWith("0")
            if (zeroFirst && !previous.digits.startsWith("0") && !isDeletion(previous.digits, digits)) {
                return Edit(previous, Problem.LEADING_ZERO)
            }
            return Edit(previous.copy(digits = digits))
        }

        /**
         * A whole international number, pasted or picked from contacts, split into its code
         * and digits. Null when no country code can be read at its start. The digits are not
         * checked here: Kenya's go through [KenyanPhone], and any other's through [problem]
         * and the length bound.
         */
        fun pasted(proposed: String): PhoneEntry? {
            val raw = proposed.dropWhile { it != '+' && it !in '0'..'9' }
            val all = raw.filter { it in '0'..'9' }
            val digits = if (raw.startsWith("+")) all else all.removePrefix("00")
            val length = codeLength(digits) ?: return null
            // "+44 (0)7911 …": the trunk 0 some people write after the code is not dialled
            // from abroad and is not part of the E.164 number.
            return PhoneEntry(digits.take(length), digits.drop(length).removePrefix("0"))
        }

        /**
         * The country code as typed in its own box: ASCII digits only, no leading 0, at most
         * three. Whether it is a whole code is [isCountryCode]'s question, asked on saving.
         */
        fun editCode(proposed: String): String =
            proposed.filter { it in '0'..'9' }.trimStart('0').take(3)

        /** As [KenyanPhone]'s: one run of digits cut out and nothing typed in. */
        private fun isDeletion(previous: String, local: String): Boolean {
            if (local.length >= previous.length) return false
            val kept = local.commonPrefixWith(previous).length
            return previous.endsWith(local.substring(kept))
        }
    }
}
