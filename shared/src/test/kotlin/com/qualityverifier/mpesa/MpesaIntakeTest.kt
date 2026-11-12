package com.qualityverifier.mpesa

import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Which messages Fundi Bora reads, and the sums it shows. */
class MpesaIntakeTest {

    private val agreedAt = 1_762_900_000_000L          // 11 November 2025, 22:26:40 UTC
    private val after = agreedAt + 60_000L
    private val before = agreedAt - 60_000L

    private val sent = "SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM. " +
        "New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh7.00."
    private val received = "SFA1B2C3D9 Confirmed.You have received Ksh1,000.00 from JANE DOE 0711123456 on " +
        "9/9/26 at 11:00 AM New M-PESA balance is Ksh2,234.56."
    private val paidTill = "SFA1B2C3D8 Confirmed. Ksh350.00 paid to NAIVAS LIMITED. on 9/9/26 at 1:00 PM.New M-PESA " +
        "balance is Ksh684.56. Transaction cost, Ksh0.00."

    private fun read(body: String, sender: String = "MPESA", receivedAt: Long = after, agreed: Long? = agreedAt) =
        MpesaIntake.read(sender, body, receivedAt, receivedAt, agreed)

    @Test
    fun `a message received after the agreement is read`() {
        val record = (read(sent) as MpesaReading.Read).record
        assertEquals("SFA1B2C3D4", record.code)
        assertEquals(MpesaDirection.OUT, record.direction)
        assertEquals(MpesaType.SEND, record.type)
        assertEquals(500.0, record.amountKes, 0.0)
        assertEquals(7.0, record.feeKes!!, 0.0)
        assertEquals(1034.56, record.balanceAfterKes!!, 0.001)
        assertEquals("254722123456", record.counterpartyPhone)
        assertEquals("JOHN DOE", record.counterpartyName)
    }

    @Test
    fun `a message received before the agreement is never read`() {
        assertEquals(MpesaReading.Ignored, read(sent, receivedAt = before))
        assertEquals(MpesaReading.Ignored, read(sent, receivedAt = agreedAt))
    }

    @Test
    fun `nothing is read while reading is off`() {
        assertEquals(MpesaReading.Ignored, read(sent, agreed = null))
    }

    @Test
    fun `other senders are ignored, Airtel Money included`() {
        assertEquals(MpesaReading.Ignored, read(sent, sender = "Safaricom"))
        assertEquals(MpesaReading.Ignored, read(sent, sender = "+254722123456"))
        assertEquals(
            MpesaReading.Ignored,
            read("P3RAIQM1E4N. Ksh 20 sent to JANE DOE 254712345678 on 16/09/26 11:20 AM. Bal:Ksh 170.0", sender = "AirtelMoney"),
        )
    }

    @Test
    fun `an M-Pesa message the parser cannot read is counted, not stored`() {
        val reading = read("SFA1B2C3Z9 Confirmed. Something entirely new happened to your account.")
        assertEquals(MpesaReading.Unread(MpesaIntake.dayOf(after)), reading)
    }

    @Test
    fun `a balance enquiry and a refused payment are not money`() {
        assertEquals(MpesaReading.NotMoney, read("Your M-PESA balance was Ksh1,234.56 on 9/9/26 at 10:00 AM."))
        assertEquals(
            MpesaReading.NotMoney,
            read("Failed. You do not have enough money in your M-PESA account to send Ksh500.00. Your M-PESA balance is Ksh20.00."),
        )
    }

    @Test
    fun `money in keeps the sender, and a till keeps its name without a number`() {
        val inbound = (read(received) as MpesaReading.Read).record
        assertEquals(MpesaDirection.IN, inbound.direction)
        assertEquals("254711123456", inbound.counterpartyPhone)

        val till = (read(paidTill) as MpesaReading.Read).record
        assertEquals(MpesaType.BUY_GOODS, till.type)
        assertEquals("NAIVAS LIMITED", till.counterpartyName)
        assertNull(till.counterpartyPhone)
    }

    @Test
    fun `the fundi's own products are not counterparties`() {
        val fuliza = read(
            "SFA1B2C3E7 Confirmed. Fuliza M-Pesa amount is Ksh 100.00. Access Fee charged Ksh 1.00. " +
                "Total Fuliza M-Pesa outstanding amount is Ksh 101.00 due on 09/10/26.",
        )
        val record = (fuliza as MpesaReading.Read).record
        assertEquals(MpesaDirection.OTHER, record.direction)
        assertNull(record.counterpartyName)
        assertNull(record.counterpartyPhone)
    }

    @Test
    fun `an unread count is filed under its Nairobi day`() {
        // 21:30 UTC on 11 November is 00:30 on 12 November in Nairobi.
        assertEquals(LocalDate.of(2026, 11, 12), MpesaIntake.dayOf(1_762_896_600_000L + 365L * 86_400_000L))
    }

    private fun entry(
        code: String,
        direction: MpesaDirection,
        amount: Double,
        at: Long,
        piece: String? = null,
        category: MpesaCategory? = null,
    ) = MpesaEntry(
        MpesaRecord(code, direction, MpesaType.SEND, amount, null, null, at, null, null),
        category = category,
        pieceId = piece,
    )

    @Test
    fun `totals count money in and out, this month and all time, and leave the fundi's own moves out`() {
        val now = 1_762_934_400_000L                          // 12 November 2025, 11:00 Nairobi
        val monthStart = MpesaSums.monthStart(now)
        assertEquals(1_761_944_400_000L, monthStart)          // 1 November 2025, 00:00 Nairobi
        val entries = listOf(
            entry("A", MpesaDirection.IN, 1000.0, now),
            entry("B", MpesaDirection.IN, 500.0, monthStart - 1),
            entry("C", MpesaDirection.OUT, 300.0, now),
            entry("D", MpesaDirection.OUT, 200.0, monthStart),
            entry("E", MpesaDirection.OTHER, 9999.0, now),
        )

        val month = MpesaSums.totals(entries, since = monthStart)
        assertEquals(MoneyTotal(1, 1000.0), month.moneyIn)
        assertEquals(MoneyTotal(2, 500.0), month.moneyOut)

        val all = MpesaSums.totals(entries)
        assertEquals(MoneyTotal(2, 1500.0), all.moneyIn)
        assertEquals(MoneyTotal(2, 500.0), all.moneyOut)

        assertEquals(MpesaTotals(MoneyTotal.ZERO, MoneyTotal.ZERO), MpesaSums.totals(emptyList()))
    }

    @Test
    fun `a piece's spending is money out assigned to it, by category`() {
        val entries = listOf(
            entry("A", MpesaDirection.OUT, 1200.0, 1, piece = "p1", category = MpesaCategory.MATERIAL_PURCHASES),
            entry("B", MpesaDirection.OUT, 300.0, 2, piece = "p1", category = MpesaCategory.MATERIAL_PURCHASES),
            entry("C", MpesaDirection.OUT, 800.0, 3, piece = "p1", category = MpesaCategory.WAGES),
            entry("D", MpesaDirection.OUT, 50.0, 4, piece = "p1"),
            // Money in for the piece is not spending.
            entry("E", MpesaDirection.IN, 5000.0, 5, piece = "p1", category = MpesaCategory.CUSTOMER_PAYMENT),
            entry("F", MpesaDirection.OUT, 400.0, 6, piece = "p2", category = MpesaCategory.MACHINE_RENTAL),
            entry("G", MpesaDirection.OUT, 999.0, 7),
        )

        val byPiece = MpesaSums.expensesByPiece(entries)

        assertEquals(
            mapOf(MpesaCategory.MATERIAL_PURCHASES to 1500.0, MpesaCategory.WAGES to 800.0, null to 50.0),
            byPiece["p1"],
        )
        assertEquals(mapOf(MpesaCategory.MACHINE_RENTAL to 400.0), byPiece["p2"])
        assertTrue(byPiece.keys == setOf("p1", "p2"))
    }
}
