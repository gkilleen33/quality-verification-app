package com.qualityverifier.text

import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.mpesa.MobileMoneyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            "research team", "United States", "section 10", "secure codes", "never stores", "academic research only",
            "could not read", "Settings", "delete every record",
        ).forEach { assertTrue("SEND lacks '$it'", send.contains(it)) }
    }

    // ------------------------------------------------------------ row 33

    /** Nathan's wording of 17 November 2026, question 2 in the three-code form row 32 needs. */
    @Test
    fun `row 33 - each question carries its approved sentences word for word`() {
        assertTrue(
            TrackerConsentText.READ.contains(
                "• For each payment it keeps the amount, the time, the type, the fee, your balance after it, " +
                    "the transaction code, the network, and the name or number of the other person, on this " +
                    "phone. PesaLog keeps the name and number only to show you who each payment was with, so " +
                    "you can follow how your business is doing.\n",
            ),
        )
        assertTrue(
            TrackerConsentText.SEND.contains(
                "• Who it was with: PesaLog sends the other person's name or number to the server so that " +
                    "the server can replace them with secure codes, made from the name, the number, and part " +
                    "of the number. The server never stores the real name or phone number.\n",
            ),
        )
    }

    @Test
    fun `row 33 - the codes are never called encrypted`() {
        listOf(TrackerConsentText.READ, TrackerConsentText.SEND).forEach {
            assertFalse(it.contains("encrypt", ignoreCase = true))
        }
    }

    /**
     * PesaLog 0.1.0 shows the 2026-11 wording and sends these hashes. They are pinned here, from
     * that wording as 0.1.0 built it, so that a change to the kept text cannot silently refuse
     * every agreement an old phone makes.
     */
    @Test
    fun `row 33 - an agreement to the old consent wording still checks`() {
        assertEquals(
            "e6eaf7f21ebdab09a0ed6d1a114ebed015e8bba6e084253874874415033a55b6",
            TrackerConsentText.published[Triple("tracker_read", "2026-11", "en")],
        )
        assertEquals(
            "2632ea1125b23c33d918f71123d9722fa98f54dcead6dedf9774efdb75e736f7",
            TrackerConsentText.published[Triple("tracker_send", "2026-11", "en")],
        )
        assertEquals(4, TrackerConsentText.published.size)
        assertTrue(TrackerConsentText.VERSION != TrackerConsentText.VERSION_2026_11)
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
