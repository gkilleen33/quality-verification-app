package com.qualityverifier.server

import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.server.mpesa.CounterpartyCoder
import com.qualityverifier.server.mpesa.CounterpartyCodes
import com.qualityverifier.text.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The one place a counterparty becomes a code. */
class CounterpartyCoderTest {

    private val coder = CounterpartyCoder("k".repeat(44))

    @Test
    fun `one number in every common form gives one code`() {
        val forms = listOf("0712345678", "+254712345678", "254712345678", "0712 345 678", "+254 712 345 678", "+2540712345678")
        val codes = forms.map { coder.code(it, null) }
        codes.forEach { assertEquals(CounterpartyBasis.PHONE, it?.basis) }
        assertEquals("forms: $forms", 1, codes.map { it?.code }.toSet().size)
    }

    @Test
    fun `a code is 64 hex characters`() {
        assertTrue(Regex("^[0-9a-f]{64}$").matches(coder.code("0712345678", null)!!.code))
    }

    @Test
    fun `the code is not an unkeyed SHA-256 of the number`() {
        val code = coder.code("0712345678", null)!!.code
        listOf("+254712345678", "254712345678", "0712345678", "phone:+254712345678").forEach {
            assertNotEquals(it, sha256Hex(it.toByteArray()), code)
        }
    }

    @Test
    fun `a different key gives a different code`() {
        val other = CounterpartyCoder("j".repeat(44))
        assertNotEquals(coder.code("0712345678", null), other.code("0712345678", null))
    }

    @Test
    fun `different numbers give different codes`() {
        assertNotEquals(coder.code("0712345678", null), coder.code("0712345679", null))
    }

    @Test
    fun `a number is preferred over a name`() {
        assertEquals(coder.code("0712345678", null), coder.code("0712345678", "JANE DOE"))
    }

    @Test
    fun `a name is trimmed, case-folded and its spaces collapsed`() {
        val code = coder.code(null, "  JANE   Wanjiru\tDOE ")
        assertEquals(CounterpartyBasis.NAME, code?.basis)
        assertEquals(code, coder.code(null, "jane wanjiru doe"))
        assertEquals("jane wanjiru doe", CounterpartyCoder.normaliseName("  JANE   Wanjiru\tDOE "))
    }

    @Test
    fun `different spellings stay different`() {
        assertNotEquals(coder.code(null, "JANE DOE"), coder.code(null, "JANE DOE HARDWARE"))
    }

    @Test
    fun `a name never shares a code with a number`() {
        // The digits of a number given as a name are coded under the name basis.
        assertNotEquals(coder.code("0712345678", null)?.code, coder.code(null, "+254712345678")?.code)
    }

    @Test
    fun `a number the sign-up rules refuse is coded as written, under the name basis`() {
        val foreign = coder.code("+255712345678", null)
        assertEquals(CounterpartyBasis.NAME, foreign?.basis)
        assertEquals(foreign, coder.code(null, "+255712345678"))
    }

    @Test
    fun `nothing usable gives no code`() {
        assertNull(coder.code(null, null))
        assertNull(coder.code("   ", "  "))
    }

    @Test
    fun `a short key is refused`() {
        assertTrue(runCatching { CounterpartyCoder("short") }.isFailure)
    }

    @Test
    fun `the key is not in toString`() {
        assertFalse(coder.toString().contains("kkkk"))
    }

    // ------------------------------------------------------------ row 32: three codes (V42)

    /** "JANE DOE 0720456123" on money sent; "jane  doe 0720***123" on money received. */
    private val send = coder.codes(phone = "254720456123", name = "JANE DOE", masked = null)
    private val receipt = coder.codes(phone = null, name = " jane  doe ", masked = "0720***123")

    @Test
    fun `row 32 - a send and a receipt from the same person get equal name and masked-number codes`() {
        assertEquals(send.nameCode, receipt.nameCode)
        assertEquals(send.maskedCode, receipt.maskedCode)
        listOf(send.nameCode, send.maskedCode).forEach { assertTrue(Regex("^[0-9a-f]{64}$").matches(it!!)) }
        // And their old codes still differ: one from the number, one from the name.
        assertEquals(CounterpartyBasis.PHONE, send.code?.basis)
        assertEquals(CounterpartyBasis.NAME, receipt.code?.basis)
        assertNotEquals(send.code, receipt.code)
    }

    @Test
    fun `row 32 - a different name with the same masked number gets a different name code`() {
        val namesake = coder.codes(phone = null, name = "JOHN DOE", masked = "0720***123")
        assertEquals(receipt.maskedCode, namesake.maskedCode)
        assertNotEquals(receipt.nameCode, namesake.nameCode)
    }

    @Test
    fun `row 32 - a different number behind the same name gets a different masked-number code`() {
        val other = coder.codes(phone = "0733456123", name = "JANE DOE", masked = null)
        assertEquals(send.nameCode, other.nameCode)
        assertNotEquals(send.maskedCode, other.maskedCode)
    }

    @Test
    fun `row 32 - the existing code is the same with or without the new inputs`() {
        assertEquals(coder.code("254720456123", "JANE DOE"), send.code)
        assertEquals(coder.code("254720456123", null), send.code)
        assertEquals(coder.code(null, "jane doe"), receipt.code)
        // A name code is the code a row coded by that name has always had.
        assertEquals(receipt.code?.code, receipt.nameCode)
    }

    @Test
    fun `row 32 - every printed form of one masked number, and every form of the full number, is one key`() {
        listOf("0720***123", "254720***123", "+254720***123", " 0720***123 ").forEach {
            assertEquals(it, "0720***123", CounterpartyCoder.maskedKey(null, it))
        }
        listOf("0720456123", "254720456123", "+254720456123", "0720 456 123").forEach {
            assertEquals(it, "0720***123", CounterpartyCoder.maskedKey(it, null))
        }
        // A full number wins over a masked one.
        assertEquals("0733***456", CounterpartyCoder.maskedKey("0733000456", "0720***123"))
    }

    @Test
    fun `row 32 - a masked value that is not what M-Pesa prints gives no masked-number code`() {
        listOf("JANE DOE", "0720456123", "0720***", "***123", "0720-123", "0720***123 JANE", "0" + "7".repeat(30) + "***123", "")
            .forEach { assertNull(it, coder.codes(phone = null, name = null, masked = it).maskedCode) }
        // A foreign number has no M-Pesa masked form.
        assertNull(coder.codes(phone = "+255712345678", name = null, masked = null).maskedCode)
    }

    @Test
    fun `row 32 - a name, a number and a masked number never share a code`() {
        val asName = coder.codes(phone = null, name = "0720***123", masked = null).nameCode
        val asMasked = coder.codes(phone = null, name = null, masked = "0720***123").maskedCode
        val asPhone = coder.codes(phone = "0720456123", name = null, masked = null)
        assertNotEquals(asName, asMasked)
        assertNotEquals(asPhone.code?.code, asPhone.maskedCode)
    }

    @Test
    fun `row 32 - nothing to code gives no codes`() {
        assertEquals(CounterpartyCodes(null, null, null), coder.codes(null, null, null))
        assertEquals(CounterpartyCodes(null, null, null), coder.codes(null, "   ", null))
    }
}
