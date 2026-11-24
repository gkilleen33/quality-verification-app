package com.qualityverifier.domain

import com.qualityverifier.domain.KenyanPhone.Problem
import com.qualityverifier.domain.PhoneEntry.Edit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone field's country code and digits (v0.7.1, row 47). */
class PhoneEntryTest {

    private val kenya = PhoneEntry()
    private val uganda = PhoneEntry("256", "")

    /** Types [text] one character at a time, as a keyboard does. */
    private fun type(start: PhoneEntry, text: String): Edit =
        text.fold(Edit(start)) { edit, c -> PhoneEntry.edit(edit.entry, edit.entry.digits + c) }

    @Test
    fun `row 47 - +254 keeps the Kenyan rules`() {
        // The default is Kenya's code, as the field has always shown.
        assertEquals(PhoneEntry.KENYA, PhoneEntry().code)
        // Nine digits, first 7, 1 or 2; a leading 0 refused; the tenth digit refused.
        assertEquals("+254712345678", type(kenya, "712345678").entry.toE164())
        assertEquals(Edit(kenya, Problem.LEADING_ZERO), PhoneEntry.edit(kenya, "0"))
        assertEquals(Edit(kenya, Problem.WRONG_START), PhoneEntry.edit(kenya, "5"))
        assertEquals(PhoneEntry(digits = "712345678"), PhoneEntry.edit(PhoneEntry(digits = "712345678"), "7123456789").entry)
        assertEquals(Problem.TOO_SHORT, PhoneEntry(digits = "71234").problem)
        assertNull(PhoneEntry(digits = "71234").toE164())
        // Pasted forms are read as KenyanPhone reads them.
        listOf("+254 712 345 678", "0712345678", "254712345678", "+2540712345678").forEach { pasted ->
            assertEquals(pasted, PhoneEntry(digits = "712345678"), PhoneEntry.edit(kenya, pasted).entry)
        }
    }

    @Test
    fun `row 47 - a pasted +44 number fills the code and the digits`() {
        assertEquals(Edit(PhoneEntry("44", "7911123456")), PhoneEntry.edit(kenya, "+44 7911 123456"))
        assertEquals(Edit(PhoneEntry("44", "7911123456")), PhoneEntry.edit(kenya, "+44 (0)7911 123456"))
        assertEquals("+447911123456", PhoneEntry.edit(kenya, "+44 7911 123456").entry.toE164())
        // A United States number has fewer digits than a Kenyan one with its code; still read.
        assertEquals(Edit(PhoneEntry("1", "4155552671")), PhoneEntry.edit(kenya, "+1 415 555 2671"))
        // A Ugandan number, refused as "not Kenyan" before v0.7.1.
        assertEquals(Edit(PhoneEntry("256", "700123456")), PhoneEntry.edit(PhoneEntry(digits = "71"), "+256700123456"))
        // And back: a +254 paste under another code returns to Kenya's code and rules.
        assertEquals(Edit(PhoneEntry(digits = "712345678")), PhoneEntry.edit(uganda, "+254 712 345 678"))
    }

    @Test
    fun `row 47 - after another code, 4 to 14 digits that make a valid E164 number`() {
        val number = type(uganda, "772123456")
        assertEquals(Edit(PhoneEntry("256", "772123456")), number)
        assertEquals("+256772123456", number.entry.toE164())
        assertTrue(Regex("""^\+[1-9][0-9]{7,14}$""").matches(number.entry.toE164()!!))
        // Fewer than four digits, or fewer than eight with the code, cannot be sent.
        assertEquals(Problem.TOO_SHORT, PhoneEntry("256", "772").problem)
        assertEquals(Problem.TOO_SHORT, PhoneEntry("1", "415555").problem)
        assertEquals("+14155552", PhoneEntry("1", "4155552").toE164())
        // No more than fifteen digits with the code: a sixteenth is refused without a message.
        val full = PhoneEntry("256", "777777777777")
        assertEquals(Edit(full), PhoneEntry.edit(full, full.digits + "7"))
        // The trunk 0 is refused, as after +254, with the abroad wording chosen by the screen.
        assertEquals(Edit(uganda, Problem.LEADING_ZERO), PhoneEntry.edit(uganda, "0"))
        // Only ASCII digits are kept: the Arabic-Indic seven and the dash are dropped.
        assertEquals(PhoneEntry("256", "77"), PhoneEntry.edit(PhoneEntry("256", "7"), "7٧-7").entry)
    }

    @Test
    fun `row 47 - a typed plus in front of the digits is not read as a new code`() {
        val typed = PhoneEntry("256", "772123456")
        assertEquals(Edit(typed), PhoneEntry.edit(typed, "+772123456"))
    }

    @Test
    fun `row 47 - only a whole country code is taken`() {
        listOf("1", "7", "44", "254", "256", "255", "20", "27", "211", "998").forEach {
            assertTrue(it, PhoneEntry.isCountryCode(it))
        }
        listOf("", "0", "25", "2", "2541", "4", "44a").forEach {
            assertFalse(it, PhoneEntry.isCountryCode(it))
        }
        assertEquals(Problem.BAD_CODE, PhoneEntry("25", "772123456").problem)
        assertNull(PhoneEntry("25", "772123456").toE164())
        // The code box keeps digits only, no leading 0, at most three.
        assertEquals("256", PhoneEntry.editCode("+0256"))
        assertEquals("441", PhoneEntry.editCode("4412"))
    }

    @Test
    fun `row 47 - a stored number opens in the field with its code`() {
        assertEquals(PhoneEntry("256", "772123456"), PhoneEntry.fromE164("+256772123456"))
        assertEquals(PhoneEntry("254", "712345678"), PhoneEntry.fromE164("+254712345678"))
        assertEquals(PhoneEntry("1", "4155552671"), PhoneEntry.fromE164("+14155552671"))
        assertEquals(PhoneEntry(), PhoneEntry.fromE164(null))
        assertEquals(PhoneEntry(), PhoneEntry.fromE164("0712345678"))
        assertEquals(PhoneEntry(), PhoneEntry.fromE164("+0712345678"))
    }
}
