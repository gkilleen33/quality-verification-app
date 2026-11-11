package com.qualityverifier.server

import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.server.mpesa.CounterpartyCoder
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
}
