package com.qualityverifier.text

import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MpesaConsentTextTest {

    @Test
    fun `the published hashes are the current texts' hashes`() {
        assertEquals(
            MpesaConsentText.sha256Of(MpesaConsentText.READ),
            MpesaConsentText.published[Triple("mpesa_read", MpesaConsentText.VERSION, "en")],
        )
        assertEquals(
            MpesaConsentText.sha256Of(MpesaConsentText.SEND),
            MpesaConsentText.published[Triple("mpesa_send", MpesaConsentText.VERSION, "en")],
        )
        assertNull(MpesaConsentText.textFor("terms"))
    }

    /** What the consent text must say (build prompt, item 6 of PR M2). */
    @Test
    fun `the texts say what is read, what is sent, and how to stop`() {
        val read = MpesaConsentText.READ
        listOf("only messages from M-Pesa", "after you agree", "never reads older", "on this phone", "Settings")
            .forEach { assertTrue("READ lacks '$it'", read.contains(it)) }
        val send = MpesaConsentText.SEND
        listOf("amount", "time", "type", "as a code", "categories, pieces and comments", "not kept", "research",
            "commercial", "Settings")
            .forEach { assertTrue("SEND lacks '$it'", send.contains(it)) }
        listOf(read, send).forEach { assertTrue("a text mentions the AI", !it.contains("AI") && !it.contains("Claude")) }
    }

    @Test
    fun `each direction offers the categories Grady listed`() {
        assertEquals(
            listOf(MpesaCategory.MATERIAL_PURCHASES, MpesaCategory.WAGES, MpesaCategory.MACHINE_RENTAL, MpesaCategory.OTHER),
            MpesaCategory.choicesFor(MpesaDirection.OUT),
        )
        assertEquals(
            listOf(MpesaCategory.CUSTOMER_PAYMENT, MpesaCategory.OTHER),
            MpesaCategory.choicesFor(MpesaDirection.IN),
        )
        assertEquals(listOf(MpesaCategory.OTHER), MpesaCategory.choicesFor(MpesaDirection.OTHER))
    }
}
