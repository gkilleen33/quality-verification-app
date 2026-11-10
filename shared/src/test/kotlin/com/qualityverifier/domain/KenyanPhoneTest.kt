package com.qualityverifier.domain

import com.qualityverifier.domain.KenyanPhone.Edit
import com.qualityverifier.domain.KenyanPhone.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KenyanPhoneTest {

    /** Types [keys] one at a time into an empty field, as a person would. */
    private fun type(keys: String): String =
        keys.fold("") { held, key -> KenyanPhone.edit(held, held + key).digits }

    @Test
    fun `nine digits sign in as the E164 number`() {
        assertEquals(Edit("712345678"), KenyanPhone.edit("", "712345678"))
        assertEquals("+254712345678", KenyanPhone.toE164("712345678"))
        assertEquals("+254112345678", KenyanPhone.toE164("112345678"))
        assertEquals("+254212345678", KenyanPhone.toE164("212345678"))
    }

    @Test
    fun `a leading 0 cannot be typed`() {
        assertEquals(Edit("", Problem.LEADING_ZERO), KenyanPhone.edit("", "0"))
        // Typed as people say it, the 0 never lands and the rest is the right number.
        assertEquals("712345678", type("0712345678"))
        assertEquals("+254712345678", KenyanPhone.toE164(type("0712345678")))
    }

    @Test
    fun `a first digit other than 7, 1 or 2 is refused`() {
        assertEquals(Edit("", Problem.WRONG_START), KenyanPhone.edit("", "5"))
        assertEquals(Edit("", Problem.WRONG_START), KenyanPhone.edit("", "9"))
        // Pasted whole, after +254 or on its own.
        assertEquals(Edit("", Problem.WRONG_START), KenyanPhone.edit("", "512345678"))
        assertEquals(Edit("", Problem.WRONG_START), KenyanPhone.edit("", "+254512345678"))
        assertNull(KenyanPhone.toE164("512345678"))
    }

    @Test
    fun `zeros after the first digit are kept`() {
        assertEquals("700123400", type("700123400"))
    }

    @Test
    fun `a whole number pasted in any common form becomes the nine digits`() {
        listOf(
            "+254712345678",
            "+254 712 345 678",
            "254712345678",
            "0712345678",
            "0712 345 678",
            "+2540712345678",
            "00254712345678",
            " 712345678 ",
        ).forEach { pasted ->
            assertEquals(pasted, Edit("712345678"), KenyanPhone.edit("", pasted))
        }
    }

    @Test
    fun `a pasted number from another country is refused, not cut to nine digits`() {
        assertEquals(Edit("", Problem.NOT_KENYAN), KenyanPhone.edit("", "+256700123456"))
        assertEquals(Edit("71", Problem.NOT_KENYAN), KenyanPhone.edit("71", "+256700123456"))
    }

    @Test
    fun `a full field takes no tenth digit`() {
        assertEquals(Edit("712345678"), KenyanPhone.edit("712345678", "7123456789"))
        assertEquals(Edit("712345678"), KenyanPhone.edit("712345678", "7129345678"))
    }

    @Test
    fun `a typed plus sign and other characters are dropped`() {
        assertEquals(Edit(""), KenyanPhone.edit("", "+"))
        assertEquals(Edit("71"), KenyanPhone.edit("71", "71#"))
        assertEquals(Edit("712"), KenyanPhone.edit("71", "71-2"))
    }

    @Test
    fun `digits from other scripts are not kept`() {
        // Arabic-Indic seven: Char.isDigit is true, and the server's pattern refuses it.
        assertEquals(Edit(""), KenyanPhone.edit("", "٧"))
    }

    @Test
    fun `deleting the first digit is allowed, and the wrong start is reported`() {
        // So the number can be corrected in place rather than retyped from empty.
        assertEquals(Edit("02345678"), KenyanPhone.edit("702345678", "02345678"))
        assertEquals(Problem.LEADING_ZERO, KenyanPhone.problem("02345678"))
        assertEquals(Problem.WRONG_START, KenyanPhone.problem("52345678"))
        // And once the front is wrong, typing a right digit there is accepted.
        assertEquals(Edit("102345678"), KenyanPhone.edit("02345678", "102345678"))
        // Deleting a selection that starts at the front counts as a deletion too.
        assertEquals(Edit("45678"), KenyanPhone.edit("712345678", "45678"))
    }

    @Test
    fun `typing over a selected number is not a deletion, so the first digit is checked`() {
        // Select all, then type 5 or 0: shorter than before, but a digit was typed in.
        assertEquals(Edit("712345678", Problem.WRONG_START), KenyanPhone.edit("712345678", "5"))
        assertEquals(Edit("712345678", Problem.LEADING_ZERO), KenyanPhone.edit("712345678", "0"))
        // Select the first three digits and type 9 over them.
        assertEquals(Edit("712345678", Problem.WRONG_START), KenyanPhone.edit("712345678", "9345678"))
    }

    @Test
    fun `direction marks copied in front of a number are dropped`() {
        listOf(
            "‪+2540712345678‬",
            "‎+254712345678",
            "‏0712345678",
            "⁦+254 712 345 678⁩",
            "‫‎ 254712345678",
        ).forEach { pasted ->
            assertEquals(pasted, Edit("712345678"), KenyanPhone.edit("", pasted))
        }
        assertEquals(Edit("", Problem.NOT_KENYAN), KenyanPhone.edit("", "‪+256700123456‬"))
    }

    @Test
    fun `fewer than nine digits cannot be sent`() {
        assertEquals(Problem.TOO_SHORT, KenyanPhone.problem(""))
        assertEquals(Problem.TOO_SHORT, KenyanPhone.problem("71234567"))
        assertNull(KenyanPhone.toE164("71234567"))
        assertNull(KenyanPhone.problem("712345678"))
    }

    @Test
    fun `nothing but nine digits is ever sent`() {
        assertNull(KenyanPhone.toE164("7123456789"))
        assertNull(KenyanPhone.toE164("71234567a"))
        assertNull(KenyanPhone.toE164("+254712345678"))
    }
}
