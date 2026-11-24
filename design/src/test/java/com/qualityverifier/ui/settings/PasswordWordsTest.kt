package com.qualityverifier.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The password screen's greyed button says what is missing, one reason at a time (row 46, P19). */
class PasswordWordsTest {

    private val hint = "At least 8 characters."

    @Test
    fun `row 46 - the password button says which field is missing, or that it is sending`() {
        assertEquals(PasswordWords.TYPE_CURRENT, PasswordWords.missing(false, "", "longenough", "longenough", hint))
        assertEquals(hint, PasswordWords.missing(false, "old", "short", "short", hint))
        assertEquals(PasswordWords.TYPE_AGAIN, PasswordWords.missing(false, "old", "longenough", "", hint))
        assertEquals(PasswordWords.SENDING, PasswordWords.missing(true, "old", "longenough", "longenough", hint))
        assertNull(PasswordWords.missing(false, "old", "longenough", "longenough", hint))
    }
}
