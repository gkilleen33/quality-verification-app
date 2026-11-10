package com.qualityverifier.domain

/**
 * A phone number as both apps' sign-up and sign-in screens take it: a fixed +254 the
 * person cannot edit, then the nine digits after it, the first of which is 7, 1, or 2.
 *
 * The field used to start as an editable "+254", so somebody who typed the number as
 * they say it produced "+2540712…". The server rewrites that to "+254712…" (PR #55,
 * V21), which made the number stored differ from the number on the screen. Holding only
 * the nine digits, with the 0 refused at the start, removes the mismatch on the phone.
 *
 * Kenya only, because the pilot's testers all use Kenyan SIMs. The server still accepts
 * any E.164 number, so an older build, or a later one that lifts this, keeps working.
 *
 * One copy for both apps: Kagua and Fundi Bora draw the field from `:design`, and this is
 * the only place that decides what the field keeps and what it sends.
 */
object KenyanPhone {

    const val COUNTRY_CODE = "+254"

    /** The digits after [COUNTRY_CODE]. */
    const val DIGITS = 9

    /** The first digits the v0.4.0 build plan allows after +254: 7, 1, or 2. */
    const val FIRST_DIGITS = "712"

    /** Why the field refused an edit, or why what it holds cannot be sent yet. */
    enum class Problem {
        /** "0712…": the trunk 0 that a number after +254 never has. */
        LEADING_ZERO,

        /** Any other first digit outside [FIRST_DIGITS], such as 5. */
        WRONG_START,

        /** Fewer than [DIGITS] digits. */
        TOO_SHORT,

        /** A pasted international number with a country code other than 254. */
        NOT_KENYAN,
    }

    /**
     * What the field holds after an edit. [refused] is set when the edit was rejected and
     * [digits] is the value from before it, so the screen can say why nothing happened.
     */
    data class Edit(val digits: String, val refused: Problem? = null)

    /**
     * Applies one change the person made, typed or pasted, to the digits held before it.
     *
     * - Only ASCII digits are kept. `Char.isDigit` would also keep other scripts' digits,
     *   which the server's pattern refuses.
     * - A paste of the whole number in any common form ("+254 712 345 678",
     *   "254712345678", "0712345678", "+2540712345678") becomes the nine digits.
     * - A pasted international number from another country is refused, rather than its
     *   first nine digits being kept as if they were Kenyan.
     * - More than [DIGITS] digits is refused without a message: the field is full.
     * - An edit that makes the first digit 0 or anything outside [FIRST_DIGITS] is
     *   refused. Deleting the first digit is allowed even when it leaves a wrong one in
     *   front, so the number can be corrected in place; [problem] then reports it.
     */
    fun edit(previous: String, proposed: String): Edit {
        val raw = proposed.trim()
        val digits = raw.filter { it in '0'..'9' }

        // Only a paste carries a + or 00 with a whole number after it: the field holds at
        // most nine digits, drops a typed + at once, and refuses a typed leading 0.
        val international = digits.length >= DIGITS + COUNTRY_DIGITS.length &&
            (raw.startsWith("+") || digits.startsWith("00"))

        val local = when {
            international -> {
                val afterCode = digits.removePrefix("00")
                if (!afterCode.startsWith(COUNTRY_DIGITS)) {
                    return Edit(previous, Problem.NOT_KENYAN)
                }
                withoutTrunkZero(afterCode.removePrefix(COUNTRY_DIGITS))
            }
            digits.length == DIGITS + COUNTRY_DIGITS.length && digits.startsWith(COUNTRY_DIGITS) ->
                digits.removePrefix(COUNTRY_DIGITS)
            else -> withoutTrunkZero(digits)
        }

        if (local.length > DIGITS) return Edit(previous)

        val wrongStart = startProblem(local)
        val previousStartOk = startProblem(previous) == null
        val isDeletion = local.length < previous.length
        if (wrongStart != null && previousStartOk && !isDeletion) {
            return Edit(previous, wrongStart)
        }
        return Edit(local)
    }

    /** Why [digits] cannot be sent yet, or null when it is a complete number. */
    fun problem(digits: String): Problem? = startProblem(digits) ?: when {
        digits.length < DIGITS -> Problem.TOO_SHORT
        else -> null
    }

    /** The number as the server takes it, "+254712345678", or null while incomplete. */
    fun toE164(digits: String): String? =
        if (digits.length == DIGITS && digits.all { it in '0'..'9' } && problem(digits) == null) {
            COUNTRY_CODE + digits
        } else {
            null
        }

    private const val COUNTRY_DIGITS = "254"

    private fun startProblem(digits: String): Problem? {
        val first = digits.firstOrNull() ?: return null
        return when {
            first == '0' -> Problem.LEADING_ZERO
            first !in FIRST_DIGITS -> Problem.WRONG_START
            else -> null
        }
    }

    /** "0712345678", ten digits with the trunk 0, is the number as people say it. */
    private fun withoutTrunkZero(digits: String): String =
        if (digits.length == DIGITS + 1 && digits.startsWith("0")) digits.substring(1) else digits
}
