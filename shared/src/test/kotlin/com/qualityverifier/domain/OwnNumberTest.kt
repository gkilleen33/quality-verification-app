package com.qualityverifier.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The studio form's check for the account's own number (row 52). */
class OwnNumberTest {

    private val own = "+254711000222"

    @Test
    fun `row 52 - the studio form refuses the fundi's own number, in its local and +254 forms`() {
        listOf("+254711000222", "254711000222", "0711000222", "711000222", "0711 000 222", "+254 711-000-222", "+2540711000222")
            .forEach { typed -> assertTrue(typed, OwnNumber.isOwn(typed, own)) }
    }

    @Test
    fun `row 52 - another number, or an own number the phone does not know yet, is let through`() {
        assertFalse(OwnNumber.isOwn("0711000223", own))
        assertFalse("not known yet: the server refuses it", OwnNumber.isOwn("0711000222", null))
        assertFalse(OwnNumber.isOwn(null, own))
    }

    @Test
    fun `row 47 - the own-number refusal holds for a number from another country`() {
        val ugandan = "+256772123456"
        listOf("+256772123456", "+256 772 123 456", "00256772123456").forEach { typed ->
            assertTrue(typed, OwnNumber.isOwn(typed, ugandan))
        }
        assertFalse(OwnNumber.isOwn("+256772123457", ugandan))
        // A Kenyan number is never a Ugandan account's own, in any form.
        assertFalse(OwnNumber.isOwn("0772123456", ugandan))
        assertFalse(OwnNumber.isOwn("+254772123456", ugandan))
        // And a Kenyan account still sees its own number, whatever the studio's form.
        assertTrue(OwnNumber.isOwn("+254 711 000 222", own))
        assertEquals("+256772123456", OwnNumber.e164("+256 772-123-456"))
        assertNull(OwnNumber.e164("+0772123456"))
    }

    @Test
    fun `a number that is not Kenyan in a form it reads is never called the own number`() {
        assertNull(OwnNumber.national("+44 20 7946 0000"))
        assertNull(OwnNumber.national("0011000222"))
        assertEquals("711000222", OwnNumber.national("+254711000222"))
    }
}
