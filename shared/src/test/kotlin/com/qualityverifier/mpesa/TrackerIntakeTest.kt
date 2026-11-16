package com.qualityverifier.mpesa

import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class TrackerIntakeTest {

    private val agreed = 1_762_900_000_000L
    private val after = agreed + 60_000

    private fun mpesa(text: String, at: Long = after) = NotificationMessage(MobileMoneyNetwork.MPESA, "MPESA", text, at)
    private fun airtel(text: String, at: Long = after) = NotificationMessage(MobileMoneyNetwork.AIRTEL, "AirtelMoney", text, at)

    private fun read(message: NotificationMessage) = TrackerIntake.read(message, agreed)

    @Test
    fun `an M-Pesa message read from a notification is one transaction`() {
        val reading = read(
            mpesa(
                "SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. " +
                    "New M-PESA balance is Ksh34.56. Transaction cost, Ksh0.00.",
            ),
        )
        val record = (reading as TrackerReading.Read).record
        assertEquals(MobileMoneyNetwork.MPESA, record.network)
        assertEquals("SFA1B2C3D5", record.record.code)
        assertEquals(MpesaType.SEND, record.record.type)
        assertEquals(MpesaDirection.OUT, record.record.direction)
        assertEquals(200.0, record.record.amountKes, 0.0)
        assertEquals(34.56, record.record.balanceAfterKes!!, 0.0)
        assertEquals("JANE MERCHANT", record.record.counterpartyName)
        assertEquals(after, record.record.occurredAt)
    }

    @Test
    fun `an Airtel Money message read from a notification is one transaction`() {
        val reading = read(airtel("U3RAGOD5ZEU. Ksh 200 received from JOHN DOE 254733000222 on 15/09/26 10:28 AM. Bal:Ksh 390.0"))
        val record = (reading as TrackerReading.Read).record
        assertEquals(MobileMoneyNetwork.AIRTEL, record.network)
        assertEquals("U3RAGOD5ZEU", record.record.code)
        assertEquals(MpesaDirection.IN, record.record.direction)
        assertEquals(200.0, record.record.amountKes, 0.0)
        assertEquals(390.0, record.record.balanceAfterKes!!, 0.0)
    }

    @Test
    fun `an Airtel id read in lower case is stored upper case`() {
        val reading = read(airtel("Trans ID: pp260909.1015.a12345. You have sent KES 500.00 to JOHN DOE 0733123456. Charges KES 11.00. Your available balance is KES 1,000.00."))
        assertEquals("PP260909.1015.A12345", (reading as TrackerReading.Read).record.record.code)
    }

    @Test
    fun `a text the SMS app shortened is unread for its day`() {
        val reading = read(mpesa("SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. New M-PESA…"))
        assertEquals(TrackerReading.Unread(MobileMoneyNetwork.MPESA, MpesaIntake.dayOf(after)), reading)
    }

    @Test
    fun `a text cut inside the amount is unread, not a smaller payment`() {
        assertTrue(read(mpesa("SFA1B2C3D5 Confirmed. Ksh1,")) is TrackerReading.Unread)
        assertTrue(read(mpesa("SFA1B2C3D5 Confirmed. Ksh1,2")) is TrackerReading.Unread)
    }

    @Test
    fun `a hidden text from a mobile money sender is unread`() {
        val reading = read(mpesa("Sensitive notification content hidden"))
        assertEquals(TrackerReading.Unread(MobileMoneyNetwork.MPESA, LocalDate.from(MpesaIntake.dayOf(after))), reading)
    }

    @Test
    fun `a message from any other sender is ignored`() {
        val other = NotificationMessage(MobileMoneyNetwork.MPESA, "JANE", "SFA1B2C3D5 Confirmed. Ksh200.00 sent to X on 9/9/26 at 5:00 PM.", after)
        assertEquals(TrackerReading.Ignored, read(other))
        // A sender that claims the other network is not trusted either.
        val crossed = NotificationMessage(MobileMoneyNetwork.AIRTEL, "MPESA", "x 1", after)
        assertEquals(TrackerReading.Ignored, read(crossed))
    }

    @Test
    fun `a message from before the agreement, or with reading off, is ignored`() {
        val text = "SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM."
        assertEquals(TrackerReading.Ignored, TrackerIntake.read(mpesa(text, at = agreed - 1), agreed))
        assertEquals(TrackerReading.Ignored, TrackerIntake.read(mpesa(text, at = agreed), agreed))
        assertEquals(TrackerReading.Ignored, TrackerIntake.read(mpesa(text), agreedAt = null))
    }

    @Test
    fun `a whole message that moved no money is not counted`() {
        assertEquals(TrackerReading.NotMoney, read(mpesa("Stand a chance to win Ksh1,000,000! Transact with M-PESA today. T&Cs apply.")))
        assertEquals(
            TrackerReading.NotMoney,
            read(mpesa("Failed. You do not have enough money in your M-PESA account to send Ksh500.00 to JOHN DOE. New M-PESA balance is Ksh100.00.")),
        )
    }

    @Test
    fun `the same message read twice gives the same code`() {
        val text = "UIGRL6LYLB Confirmed. You have received Ksh450.00 from IM BANK LIMITED- APP on 16/9/26 at 8:29 PM. New M-PESA balance is Ksh450.00. Buy goods with M-PESA."
        val first = (read(mpesa(text)) as TrackerReading.Read).record
        val second = (read(mpesa(text, at = after + 5_000)) as TrackerReading.Read).record
        assertEquals(first.record.code, second.record.code)
        assertEquals(first.record.amountKes, second.record.amountKes, 0.0)
    }

    /**
     * Every real sample in MobileMoneyParserTest that reads whole, cut at every length: each cut
     * is either not stored, or stored as the same payment. Never a different code (a second row
     * for one payment), amount or person.
     */
    @Test
    fun `no cut of a real message is stored as a different payment`() {
        val samples = MPESA_SAMPLES.map(::mpesa) + AIRTEL_SAMPLES.map(::airtel)
        samples.forEach { whole ->
            val full = read(whole)
            assertTrue("expected the whole sample to read: ${whole.text}", full is TrackerReading.Read)
            val expected = (full as TrackerReading.Read).record.record
            for (length in 1 until whole.text.length) {
                val cut = whole.copy(text = whole.text.substring(0, length))
                val reading = read(cut)
                if (reading !is TrackerReading.Read) continue
                val got = reading.record.record
                val where = "cut at $length: '${cut.text}'"
                assertEquals(where, expected.code, got.code)
                assertEquals(where, expected.amountKes, got.amountKes, 0.0)
                assertEquals(where, expected.type, got.type)
                assertEquals(where, expected.direction, got.direction)
                assertEquals(where, expected.counterpartyName, got.counterpartyName)
                assertEquals(where, expected.counterpartyPhone, got.counterpartyPhone)
                assertTrue(where, got.feeKes == null || got.feeKes == expected.feeKes)
                assertTrue(where, got.balanceAfterKes == null || got.balanceAfterKes == expected.balanceAfterKes)
            }
        }
    }

    @Test
    fun `every real Airtel ending reads as whole`() {
        AIRTEL_SAMPLES.forEach { assertTrue(it, TrackerIntake.looksWhole(MobileMoneyNetwork.AIRTEL, it)) }
        MPESA_SAMPLES.forEach { assertTrue(it, TrackerIntake.looksWhole(MobileMoneyNetwork.MPESA, it)) }
    }

    private companion object {
        /** From MobileMoneyParserTest: the M-Pesa wordings it reads as money, names invented there. */
        val MPESA_SAMPLES = listOf(
            "SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. New M-PESA balance is Ksh34.56. Transaction cost, Ksh0.00.",
            "SFA1B2C3D6 Confirmed. Ksh1,200.00 sent to KPLC PREPAID for account 12345678 on 9/9/26 at 12:00 PM New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh0.00.",
            "SFA1B2C3D8 Confirmed. Ksh350.00 paid to NAIVAS LIMITED. on 9/9/26 at 1:00 PM.New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00. Amount you can transact within the day is 499,150.00.",
            "SFA1B2C3D9 Confirmed.You have received Ksh1,000.00 from JANE DOE 0711123456 on 9/9/26 at 11:00 AM  New M-PESA balance is Ksh2,234.56. Separate personal and business funds through Pochi la Biashara on *334#.",
            "SFA1B2C3E0 Confirmed.You have received Ksh5,000.00 from EQUITY BANK KENYA LIMITED 300300 on 9/9/26 at 9:00 AM New M-PESA balance is Ksh7,234.56.",
            "SFA1B2C3E1 Confirmed.on 9/9/26 at 2:00 PMWithdraw Ksh500.00 from 123456 - JOHN AGENT SHOP New M-PESA balance is Ksh184.56. Transaction cost, Ksh29.00. Amount you can transact within the day is 498,650.00.",
            "SFA1B2C3E2 Confirmed.on 9/9/26 at 3:00 PMGive Ksh1,000.00 cash to JOHN AGENT SHOP New M-PESA balance is Ksh1,184.56.",
            "SFA1B2C3E3 confirmed.You bought Ksh50.00 of airtime on 9/9/26 at 4:00 PM.New M-PESA balance is Ksh1,134.56. Transaction cost, Ksh0.00.",
            "RK94V6J95E Confirmed. Fuliza M-PESA amount is Ksh 90.00. Interest charged Ksh 0.90. Total Fuliza M-PESA outstanding amount is Ksh 233.72 due on 08/12/23. To check daily charges, Dial *234*0#OK Select Query Charges",
            "FLZ1AAAAA1 Confirmed. You have used Fuliza M-PESA. Amount used Ksh500.00 on 20/7/26 at 8:20 PM. Access fee charged Ksh10.00. Total Fuliza M-PESA outstanding is Ksh510.00. New M-PESA balance is Ksh0.00.",
            "SD38YVVQ1A Confirmed. Ksh 500.00 from your M-PESA has been used to partially pay your Fuliza M-PESA. Outstanding Fuliza M-PESA is Ksh 957.83 due on 03/05/24.",
            "UIFLY6JZNS Confirmed. You have received Ksh20.00 from AIRTEL MONEY - JANE DOE 100123456 on 15/9/26 at 2:24 PM New M-Pesa balance is Ksh1,234.00. Buy goods with M-Pesa",
            "UINRL7DIVV Confirmed.You have received Ksh5.00 from jane  doe 0720***123 on 23/9/26 at 9:30 AM  New M-PESA balance is Ksh34,425.00. Invest & earn daily interest with ZIIDI on https://saf.cx/cF6ir",
            "UIGRL6LYLB Confirmed. You have received Ksh450.00 from IM BANK LIMITED- APP on 16/9/26 at 8:29 PM. New M-PESA balance is Ksh450.00. Buy goods with M-PESA.",
            "MSW1AAAAA1 Confirmed. Ksh1,000.00 transferred to M-Shwari account on 1/7/26 at 10:00 AM. New M-PESA balance is Ksh200.00.",
            "SFA1B2C3E7 Confirmed. Reversal of transaction SFA1B2C3E6 has been successfully reversed on 9/9/26 at 8:00 PM and Ksh500.00 is credited to your M-PESA account. New M-PESA balance is Ksh1,500.00.",
        )

        /** From MobileMoneyParserTest: Airtel Money's real 2026 wordings, and the documented ones. */
        val AIRTEL_SAMPLES = listOf(
            "TID:93RAIRQ77FS. Received Ksh 10 from JANE DOE 254722000111 on 16/09/26 11:31 AM. Bal:Ksh 190.0 Sender TID:UIG4R73WQV.",
            "P3RAIQM1E4N. Ksh 20 sent to JANE DOE 254722000111 on 16/09/26 11:20 AM. Bal:Ksh 170.0",
            "U3RAGOD5ZEU. Ksh 200 received from JOHN DOE 254733000222 on 15/09/26 10:28 AM. Bal:Ksh 390.0",
            "77687700737 Confirmed. Bundle purchase successful of Ksh 15 via Airtel Networks Kenya Ltd on 13/09/26 at 06:17 PM. Bal: Ksh 94.0.",
            "Y3R43N9LBUN. Ksh 20 paid to CORNER MINI SUPERMARKET   1 account 123456 on 11/09/2026 19:15. Fee Ksh 0. Bal:Ksh 109.0. MPESA ID:UIBS06VLWN",
            "73R28LWH136. Ksh 1,400 paid to Lipa Na Mpesa via Airtel Money on 10/09/26 at 10:49 AM. Fee: Ksh 0. Bal: Ksh 833.0. MPESA ID: UIAS06O8OA",
            "63RAXZPKKIK Confirmed. Ksh 100 successfully paid to KCB BANK AGENT on 16/09/26 at 07:07 PM. Fee: Ksh 0.0. Bal: Ksh 60.0.",
            "E3RAXX528TC. KCB BANK AGENT has deposited Ksh 150 to your account on 16/09/26 07:04 PM. Bal: Ksh 160.0.",
            "Corner  General Shop has deposited Ksh 100 to your account on 16/09/26 07:11 PM.Bal: Ksh 160.0. TID V3RAY6227A9.",
            "N3RAY7QBHIP Confirmed. You have withdrawn Ksh 100 from 400604 Corner  General Shop on 16/09/26 at 07:13 PM. Fee: Ksh 11 Bal Ksh 49.0.",
            "Trans ID: PP260909.1015.A12345. You have sent KES 500.00 to JOHN DOE 0733123456. Charges KES 11.00. Your available balance is KES 1,000.00.",
            "Airtel Money: You sent KES 1,500.00 to JOHN DOE (0712345678) on 15/12/2024 at 2:30 PM. Your new balance is KES 8,500.00. Transaction ID: AIR123456789. Charges: KES 15.00.",
        )
    }
}
