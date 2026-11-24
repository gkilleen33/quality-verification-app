package com.qualityverifier.domain

import com.qualityverifier.domain.KenyanPhone.Problem
import com.qualityverifier.domain.PhoneEntry.Edit
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A number picked from the phone's contacts, in each form a contact is saved in. The field
 * decides what the digits mean; this only checks that a picked number reaches it whole and
 * that a number the field cannot take is refused with a reason.
 */
class ContactNumberTest {

    private fun kenyan(digits: String) = Edit(PhoneEntry(PhoneEntry.KENYA, digits))

    @Test
    fun `every Kenyan form becomes the nine digits`() {
        listOf(
            "0712345678",
            "0712 345 678",
            "+254712345678",
            "+254 712 345 678",
            "254712345678",
            "+2540712345678",
            "00254712345678",
            // The direction marks a contact card often starts with.
            "‪+254 712 345 678‬",
        ).forEach { picked ->
            assertEquals(picked, kenyan("712345678"), ContactNumber.read(picked))
        }
        assertEquals("+254712345678", ContactNumber.read("0712 345 678").entry.toE164())
    }

    @Test
    fun `row 47 - a number from another country fills its code and digits`() {
        assertEquals(Edit(PhoneEntry("1", "4155552671")), ContactNumber.read("+1 415 555 2671"))
        assertEquals(Edit(PhoneEntry("256", "700000000")), ContactNumber.read("+256 700 000 000"))
        assertEquals(Edit(PhoneEntry("44", "7700900123")), ContactNumber.read("0044 7700 900123"))
        // The (0) some people write after the code is not part of the number.
        assertEquals(Edit(PhoneEntry("44", "7700900123")), ContactNumber.read("+44 (0)7700 900123"))
        assertEquals("+256700000000", ContactNumber.read("+256 700 000 000").entry.toE164())
    }

    @Test
    fun `row 47 - a foreign number that cannot be one is refused under its own code`() {
        // Nine digits or fewer after a + read as Kenyan (a stray +, below), so this is too
        // short under +254: refused either way.
        assertEquals(Edit(PhoneEntry(), Problem.TOO_SHORT), ContactNumber.read("+256 700"))
        assertEquals(Edit(PhoneEntry("256", ""), Problem.TOO_LONG), ContactNumber.read("+256 7000 0000 0000 0"))
    }

    @Test
    fun `row 47 - a Kenyan number written with a stray plus stays Kenyan`() {
        // "+7…" is Russia's code, but nine digits are a Kenyan number with a stray +.
        assertEquals(kenyan("712345678"), ContactNumber.read("+712345678"))
        assertEquals(kenyan("712345678"), ContactNumber.read("+0712345678"))
        assertEquals(kenyan("712345678"), ContactNumber.read("+0 712 345 678"))
    }

    @Test
    fun `row 47 - a leading 0 after another country's code is kept, a bracketed one dropped`() {
        assertEquals(Edit(PhoneEntry("225", "0712345678")), ContactNumber.read("+225 07 12 34 56 78"))
        assertEquals(Edit(PhoneEntry("44", "7700900123")), ContactNumber.read("+44 (0)7700 900123"))
    }

    @Test
    fun `a number that is not a mobile number is refused with the field's reason`() {
        assertEquals(Edit(PhoneEntry(), Problem.WRONG_START), ContactNumber.read("0512345678"))
        assertEquals(Edit(PhoneEntry(), Problem.TOO_SHORT), ContactNumber.read("0712 345"))
        assertEquals(Edit(PhoneEntry(), Problem.TOO_SHORT), ContactNumber.read("no number"))
        // Too many digits for a local number, and no country code to say whose it is.
        assertEquals(Edit(PhoneEntry(), Problem.NOT_KENYAN), ContactNumber.read("71234567890"))
    }
}
