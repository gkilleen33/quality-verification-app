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
    fun `a number that is not Kenyan in a form it reads is never called the own number`() {
        assertNull(OwnNumber.national("+44 20 7946 0000"))
        assertNull(OwnNumber.national("0011000222"))
        assertEquals("711000222", OwnNumber.national("+254711000222"))
    }
}
