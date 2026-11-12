package com.qualityverifier.domain

import com.qualityverifier.domain.KenyanPhone.Edit
import com.qualityverifier.domain.KenyanPhone.Problem

/**
 * A phone number as the system contact picker hands it over, read by the phone field's
 * rules ([KenyanPhone]) into the nine digits the field holds.
 *
 * A picked number arrives whole, in whatever form the contact was saved: "0712 345 678",
 * "+254 712 345 678", "254712345678", or a number from another country. The field reads a
 * paste one edit at a time and answers "nothing happened" to a number it cannot hold; a
 * picked number needs a reason instead, so the person can correct it or add the worker
 * without one. So the forms are taken apart here, and the start rule and the E.164 form
 * stay [KenyanPhone]'s.
 *
 * The result is [Edit.digits] when the number is usable and [Edit.refused] when it is not;
 * [Edit.digits] is empty then, so the field below the picker starts blank for a correction.
 */
object ContactNumber {

    fun read(raw: String): Edit {
        // Anything before the first digit or +: spaces, and the direction marks a contact
        // card often starts with, which KenyanPhone.edit drops the same way.
        val cleaned = raw.dropWhile { it != '+' && it !in '0'..'9' }
        val digits = cleaned.filter { it in '0'..'9' }
        if (digits.isEmpty()) return Edit("", Problem.TOO_SHORT)

        // A + or 00 says the number carries its country code, whatever its length: a
        // foreign number shorter than a Kenyan one with its code is still foreign.
        val international = cleaned.startsWith("+") || digits.startsWith("00")
        val local = when {
            international -> {
                val afterCode = digits.removePrefix("00")
                if (!afterCode.startsWith(COUNTRY_DIGITS)) return Edit("", Problem.NOT_KENYAN)
                // "+2540712…": the trunk 0 a number after +254 never has (see KenyanPhone).
                afterCode.removePrefix(COUNTRY_DIGITS).removePrefix("0")
            }
            digits.length == KenyanPhone.DIGITS + COUNTRY_DIGITS.length && digits.startsWith(COUNTRY_DIGITS) ->
                digits.removePrefix(COUNTRY_DIGITS)
            // "0712…", as people say it, at any length: the 0 is the trunk prefix.
            else -> digits.removePrefix("0")
        }

        val problem = when {
            local.length < KenyanPhone.DIGITS -> Problem.TOO_SHORT
            // Too many digits for a mobile number, and no code to say whose it is.
            local.length > KenyanPhone.DIGITS -> Problem.NOT_KENYAN
            else -> KenyanPhone.problem(local)
        }
        return if (problem == null) Edit(local) else Edit("", problem)
    }

    private const val COUNTRY_DIGITS = "254"
}
