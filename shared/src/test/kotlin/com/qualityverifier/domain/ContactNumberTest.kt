package com.qualityverifier.domain

import com.qualityverifier.domain.KenyanPhone.Edit
import com.qualityverifier.domain.KenyanPhone.Problem
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A number picked from the phone's contacts, in each form a contact is saved in. The field
 * decides what the digits mean; this only checks that a picked number reaches it whole and
 * that a number the field cannot take is refused with a reason.
 */
class ContactNumberTest {

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
            assertEquals(picked, Edit("712345678"), ContactNumber.read(picked))
        }
        assertEquals("+254712345678", KenyanPhone.toE164(ContactNumber.read("0712 345 678").digits))
    }

    @Test
    fun `a number from another country is refused, not read as Kenyan`() {
        assertEquals(Edit("", Problem.NOT_KENYAN), ContactNumber.read("+1 415 555 2671"))
        assertEquals(Edit("", Problem.NOT_KENYAN), ContactNumber.read("+256 700 000 000"))
        assertEquals(Edit("", Problem.NOT_KENYAN), ContactNumber.read("0044 7700 900123"))
    }

    @Test
    fun `a number that is not a mobile number is refused with the field's reason`() {
        assertEquals(Edit("", Problem.WRONG_START), ContactNumber.read("0512345678"))
        assertEquals(Edit("", Problem.TOO_SHORT), ContactNumber.read("0712 345"))
        assertEquals(Edit("", Problem.TOO_SHORT), ContactNumber.read("no number"))
        // Too many digits for a local number, and no country code to say whose it is.
        assertEquals(Edit("", Problem.NOT_KENYAN), ContactNumber.read("71234567890"))
    }
}
