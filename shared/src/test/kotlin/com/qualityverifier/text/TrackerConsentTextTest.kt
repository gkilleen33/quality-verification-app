package com.qualityverifier.text

import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.mpesa.MobileMoneyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerConsentTextTest {

    @Test
    fun `the published hashes are the current texts' hashes`() {
        assertEquals(
            TrackerConsentText.sha256Of(TrackerConsentText.READ),
            TrackerConsentText.published[Triple("tracker_read", TrackerConsentText.VERSION, "en")],
        )
        assertEquals(
            TrackerConsentText.sha256Of(TrackerConsentText.SEND),
            TrackerConsentText.published[Triple("tracker_send", TrackerConsentText.VERSION, "en")],
        )
        assertNull(TrackerConsentText.textFor("mpesa_read"))
    }

    /**
     * What the build prompt asks the wording to say: what is read, that notification access
     * shows every notification and the rest are ignored, what stays on the phone, that
     * sending is for research, both can be turned off, and the server copy can be deleted.
     * And the limit of reading notifications, on the screen's own text.
     */
    @Test
    fun `the texts say what is read, what is ignored, what is sent, and how to stop or delete`() {
        val read = TrackerConsentText.READ
        listOf(
            "M-Pesa", "Airtel Money", "every notification", "ignores every notification except",
            "does not store or send any other notification", "after you agree",
            "before you installed it", "dismissed while it was off", "never reads your SMS inbox",
            "on this phone", "Settings",
        ).forEach { assertTrue("READ lacks '$it'", read.contains(it)) }
        val send = TrackerConsentText.SEND
        listOf(
            "research team", "United States", "section 10", "as a code", "not kept", "academic research only",
            "could not read", "Settings", "delete every record",
        ).forEach { assertTrue("SEND lacks '$it'", send.contains(it)) }
    }

    @Test
    fun `the questions name every network PesaLog reads`() {
        MobileMoneyNetwork.entries.forEach {
            assertTrue("READ does not name ${it.displayName}", TrackerConsentText.READ.contains(it.displayName))
        }
    }

    @Test
    fun `each network is the parser's own`() {
        assertEquals(MobileMoneyParser.NETWORK_MPESA, MobileMoneyNetwork.MPESA.id)
        assertEquals(MobileMoneyParser.NETWORK_AIRTEL, MobileMoneyNetwork.AIRTEL.id)
    }
}
