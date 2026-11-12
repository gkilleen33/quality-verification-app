// Ported from Drew Burd's money-tracking-kenya (https://github.com/drewburd4/money-tracking-kenya),
// commit 5355a31: the file of the same name in android/app/src/test/java/com/utafiti/sms/.
// Changed only in its package; the cases are his, run unchanged against the ported parser.
// Used under the MIT licence below, which the licence requires to travel with the file.
//
// MIT License
//
// Copyright (c) 2026 Drew Burd
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.qualityverifier.mpesa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The wordings the field left unread up to October 2026, now built in (APK 1.34; the same set
 * runs as server rules 3-19 for older phones), plus the wallet balance every text prints. The
 * texts are rebuilt from the de-identified templates the phones uploaded, so the boilerplate
 * is real and the names, numbers and figures are not.
 */
class BuiltInWordings134Test {
    private val ts = 1_760_000_000_000L
    private val salt = "test-salt"
    private fun mp(body: String) = MobileMoneyParser.parse(body, "MPESA", ts, RuleSet.EMPTY, salt)
    private fun at(body: String) = MobileMoneyParser.parse(body, "AirtelMoney", ts, RuleSet.EMPTY, salt)

    private fun ownMove(t: Transaction?, type: String, amount: Double, party: String) {
        assertEquals(type, t!!.type)
        assertEquals("other", t.direction)
        assertEquals("self", t.counterpartyKind)
        assertEquals(amount, t.amount!!, 0.001)
        assertEquals(party, t.counterparty)
        assertEquals("ok", t.parseStatus)
    }

    @Test
    fun pochiMovesAreOwnAccountMovesWithTheFee() {
        val out = mp("TJR4K2M9PQ Confirmed, Ksh 1,500.00 has been moved from your Pochi account to your M-PESA account on 12/10/26 at 3:41 PM.. New Pochi balance is Ksh 200.00. New M-PESA balance is Ksh 1,650.00. Transaction cost, Ksh 5.00.")
        ownMove(out, "savings_withdraw", 1500.0, "POCHI")
        assertEquals(5.0, out!!.cost!!, 0.001)
        assertEquals(1650.0, out.balanceAfter!!, 0.001)
        val into = mp("TJR4K2M9PR Confirmed, Ksh500.00 has been moved from your M-PESA account to your Pochi account on 12/10/26 at 9:05 AM.. New Pochi balance is Ksh 700.00. New M-PESA balance is Ksh 1,150.00. Transaction cost, Ksh 0.00.")
        ownMove(into, "savings_deposit", 500.0, "POCHI")
    }

    @Test
    fun kcbMovesWithTheMisspeltVerbAndLoanRepayments() {
        ownMove(mp("TJR4K2M9PS Confirmed. You have transfered Ksh 2,000.00 from your KCB M-PESA account on 12/10/26 at 3:41 PM. KCB M-PESA Account balance is Ksh 100.00. New M-PESA balance is Ksh 3,150.00."),
            "savings_withdraw", 2000.0, "KCB M-PESA")
        ownMove(mp("TJR4K2M9PT Confirmed. Ksh 1,000.00 transfered to KCB M-PESA account on 12/10/26 at 3:41 PM. New M-PESA balance is Ksh 2,150.00, new KCB M-PESA Saving account balance is Ksh 1,100.00."),
            "savings_deposit", 1000.0, "KCB M-PESA")
        ownMove(mp("TJR4K2M9PU Confirmed. Your loan repayment of Ksh 1,050.00 from your M-PESA account to KCB M-PESA on 12/10/26 at 3:41 PM is successful. Your M-PESA balance is Ksh 1,100.00."),
            "savings_deposit", 1050.0, "KCB M-PESA LOAN")
        ownMove(mp("TJR4K2M9PV Confirmed. Loan of Ksh 1,075.00 repaid from your M-PESA account on 12/10/26 at 9:41 AM. M-PESA balance is Ksh 25.00.Your loan is repaid in full, and the deposit has been moved to your Savings Account. New M-Shwari balance is Ksh 200.00. Transaction cost Kshs 0.00"),
            "savings_deposit", 1075.0, "M-SHWARI LOAN")
        ownMove(mp("TJR4K2M9PW Confirmed. Loan of Ksh 500.00 repaid from M-PESA on 12/10/26 at 9:41 AM. Loan balance is Ksh 575.00. Transaction cost Kshs 0.00"),
            "savings_deposit", 500.0, "M-SHWARI LOAN")
        // The correctly spelt wording still reads through the older rules, with the same party.
        ownMove(mp("SFA1B2C3E6 Confirmed. You have transferred Ksh200.00 from your KCB M-PESA account on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh384.56."),
            "savings_withdraw", 200.0, "KCB M-PESA")
    }

    @Test
    fun noticesThatMoveNoMoneyAreDropped() {
        val notices = listOf(
            "TJR4K2M9PX Confirmed . Your M-Shwari Deposit Account balance is Ksh 200.00 .Transaction cost Ksh 0.00.",
            "TJR4K2M9PY confirmed. Your KCB M-PESA balance is: 1. KCB M-PESA Savings Account; 1,100.00 at 3:41 PM.",
            "TJR4K2M9PZ confirmed. Your KCB M-PESA balance is: 1. KCB M-PESA Savings Account; 1,100.00 2. KCB M-PESA Loan; 0.00 at 9:41 AM.",
            "TJR4K2M9QA Confirmed. Your loan limit is 3,000.00.Access fee of 1% and interest of 7.5% is applicable, early repayment attracts one time fee of 1%, for Terms and Conditions visit www.safaricom.co.ke or Safaricom shops. Transaction Cost Ksh 0.00",
            "TJR4K2M9QB Confirmed. . Your M-Shwari loan limit is Ksh 3,000.00. Available limit is Ksh 3,000.00. Did you know you can top up your M-Shwari loan today? Transaction cost Ksh 0.00.",
            "TJR4K2M9QC Confirmed. You have an outstanding loan of Ksh 1,075.00 due on 11/11/26 9:41 AM. Your available limit is Ksh-1,925.00 which you can access anytime within the first 30 days of your current loan. Transaction cost Ksh 0.00.",
            "TJR4K2M9QD Confirmed. Your KCB M-PESA loan request will be processed shortly.",
            "TJR4K2M9QE Confirmed.Your M-PESA Pochi Account balance was Ksh 700.00 on 12/10/26 at 3:41 PM. Transaction cost, Ksh 0.00.",
        )
        for (n in notices) assertNull(n, mp(n))
    }

    @Test
    fun reversalWordingsCarryTheReversedCode() {
        val t = mp("TJR4K2M9QG confirmed. Reversal of transaction TJR4K2M9PR has been successfully reversed on 12/10/26 at 3:41 PM and Ksh 500.00 is credited to your M-PESA account. New M-PESA account balance is Ksh 650.00.")!!
        assertEquals("reversal", t.type)
        assertEquals("TJR4K2M9PR", t.reversedRef)
        assertEquals(500.0, t.amount!!, 0.001)
        assertEquals(650.0, t.balanceAfter!!, 0.001)
        val done = mp("TJR4K2M9QH confirmed. Reversal of transaction TJR4K2M9PS has been completed successfully on 12/10/26 at 3:41 PM and Ksh 2,000.00 has been credited to your M-Pesa Account.")!!
        assertEquals("reversal", done.type)
        assertEquals("TJR4K2M9PS", done.reversedRef)
    }

    @Test
    fun airtelBankReceiptAndAirtimeForALine() {
        val bank = at("Q3RAIRQ77FS. You have received Ksh 1,000 from EQUITY BANK LIMITED on 12/10/26 15:41 PM. Bal: Ksh 1,190.0")!!
        assertEquals("receive", bank.type)
        assertEquals("in", bank.direction)
        assertEquals("business", bank.counterpartyKind)
        assertEquals(1000.0, bank.amount!!, 0.001)
        assertEquals(1190.0, bank.balanceAfter!!, 0.001)
        val line = at("12345678901 Successful. Airtime top up for line 733123456 of Ksh 50 is successful. Bal: Ksh 140.0. To check your airtime balance, dial *131#")!!
        assertEquals("airtime", line.type)
        assertEquals("out", line.direction)
        assertEquals("self", line.counterpartyKind)
        assertEquals(50.0, line.amount!!, 0.001)
        assertEquals("partial", line.parseStatus)
        assertEquals(140.0, line.balanceAfter!!, 0.001)
    }

    @Test
    fun balanceIsTheWalletFigureNeverTheProductFigure() {
        assertEquals(1234.56, mp("SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh1,234.56. Transaction cost, Ksh7.00.")!!.balanceAfter!!, 0.001)
        // Beside a KCB or M-Shwari figure, the wallet's own balance is the one kept.
        assertEquals(3150.0, mp("TJR4K2M9PS Confirmed. You have transfered Ksh 2,000.00 from your KCB M-PESA account on 12/10/26 at 3:41 PM. KCB M-PESA Account balance is Ksh 100.00. New M-PESA balance is Ksh 3,150.00.")!!.balanceAfter!!, 0.001)
        assertEquals(184.56, mp("SFA1B2C3E5 Confirmed. Ksh200.00 transferred to M-Shwari account on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh184.56. New M-Shwari balance is Ksh200.00.")!!.balanceAfter!!, 0.001)
        assertNull(mp("SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM.")!!.balanceAfter)
        assertEquals(170.0, at("P3RAIQM1E4N. Ksh 20 sent to JANE DOE 254722000111 on 16/09/26 11:20 AM. Bal:Ksh 170.0")!!.balanceAfter!!, 0.001)
        assertEquals(1000.0, at("Trans ID: PP260909.1015.A12345. You have sent KES 500.00 to JOHN DOE 0733123456. Charges KES 11.00. Your available balance is KES 1,000.00.")!!.balanceAfter!!, 0.001)
        // An unread text keeps its balance too, for the day a rule reads it.
        val unknown = mp("TJR4K2M9ZZ Confirmed. Something new happened with Ksh 40.00 on 12/10/26 at 3:41 PM. New M-PESA balance is Ksh 60.00.")!!
        assertEquals("unknown", unknown.type)
        assertEquals(60.0, unknown.balanceAfter!!, 0.001)
    }
}
