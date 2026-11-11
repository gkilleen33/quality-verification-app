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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Synthetic samples in the public M-Pesa wording plus real 2026 wordings with the identifying
 * details replaced (see docs/SMS_TEMPLATES.md).
 */
class MobileMoneyParserTest {
    private val ts = 1_757_400_000_000L
    private val salt = "test-salt"

    private fun mpesa(body: String, rules: RuleSet = RuleSet.EMPTY) = MobileMoneyParser.parse(body, "MPESA", ts, rules, salt)
    private fun airtel(body: String, rules: RuleSet = RuleSet.EMPTY) = MobileMoneyParser.parse(body, "AirtelMoney", ts, rules, salt)
    private fun rule(vararg pairs: Pair<String, Any?>): Map<String, Any?> = mapOf(*pairs)

    @Test
    fun senderClassification() {
        assertEquals("mpesa", MobileMoneyParser.networkForSender("MPESA"))
        assertEquals("mpesa", MobileMoneyParser.networkForSender("M-PESA"))
        assertEquals("mpesa", MobileMoneyParser.networkForSender("Safaricom M-PESA"))
        assertEquals("airtel", MobileMoneyParser.networkForSender("AirtelMoney"))
        assertEquals("airtel", MobileMoneyParser.networkForSender("Airtel Money"))
        assertNull(MobileMoneyParser.networkForSender("Safaricom"))
        assertNull(MobileMoneyParser.networkForSender("MPESA-Kenya"))
        assertNull(MobileMoneyParser.networkForSender("+254722123456"))
        assertNull(MobileMoneyParser.networkForSender(null))
    }

    @Test
    fun sendMoneyToPerson() {
        val t = mpesa(
            "SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM. " +
                "New M-PESA balance is Ksh1,234.56. Transaction cost, Ksh7.00. " +
                "Amount you can transact within the day is 499,500.00."
        )!!
        assertEquals("send", t.type)
        assertEquals("out", t.direction)
        assertEquals(500.0, t.amount!!, 0.001)
        assertEquals(7.0, t.cost!!, 0.001)
        assertEquals("JOHN DOE", t.counterparty)
        assertEquals("254722123456", t.counterpartyPhone)
        assertEquals("person", t.counterpartyKind)
        assertEquals("safaricom", t.counterpartyNetwork)
        assertEquals("SFA1B2C3D4", t.ref)
        assertEquals("ok", t.parseStatus)
        assertNotNull(t.counterpartyHash)
    }

    @Test
    fun amountSpellingsAreAccepted() {
        val kshs = mpesa("SFA1B2C3D4 Confirmed. KShs500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM.")!!
        assertEquals("send", kshs.type)
        assertEquals(500.0, kshs.amount!!, 0.001)
        val dotted = mpesa("SFA1B2C3D4 Confirmed. Ksh.500 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM.")!!
        assertEquals(500.0, dotted.amount!!, 0.001)
    }

    @Test
    fun sendMoneyToPochiHasNoPhone() {
        val t = mpesa("SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. New M-PESA balance is Ksh34.56. Transaction cost, Ksh0.00.")!!
        assertEquals("send", t.type)
        assertEquals("JANE MERCHANT", t.counterparty)
        assertNull(t.counterpartyPhone)
        assertEquals("business", t.counterpartyKind)
        assertEquals("unknown", t.counterpartyNetwork)
    }

    @Test
    fun paybill() {
        val t = mpesa("SFA1B2C3D6 Confirmed. Ksh1,200.00 sent to KPLC PREPAID for account 12345678 on 9/9/26 at 12:00 PM New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh0.00.")!!
        assertEquals("paybill", t.type)
        assertEquals("out", t.direction)
        assertEquals(1200.0, t.amount!!, 0.001)
        assertEquals("KPLC PREPAID", t.counterparty)
        assertEquals("business", t.counterpartyKind)
    }

    @Test
    fun bundlePurchaseViaPaybillIsFlagged() {
        val t = mpesa("SFA1B2C3D7 Confirmed. Ksh50.00 sent to SAFARICOM DATA BUNDLES for account 0722123456 on 9/9/26 at 12:30 PM New M-PESA balance is Ksh984.56. Transaction cost, Ksh0.00.")!!
        assertEquals("bundle", t.type)
        assertEquals("out", t.direction)
    }

    @Test
    fun bundleFlagDoesNotCatchOrdinaryBusinessNames() {
        val sacco = mpesa("SFA1B2C3D7 Confirmed. Ksh5,000.00 sent to SAFARICOM INVESTMENT CO-OPERATIVE for account 99887 on 9/9/26 at 12:30 PM New M-PESA balance is Ksh984.56.")!!
        assertEquals("paybill", sacco.type)
        val shop = mpesa("SFA1B2C3D8 Confirmed. Ksh350.00 paid to AIRTIME PLUS SHOP. on 9/9/26 at 1:00 PM.New M-PESA balance is Ksh684.56.")!!
        assertEquals("buy_goods", shop.type)
    }

    @Test
    fun buyGoods() {
        val t = mpesa("SFA1B2C3D8 Confirmed. Ksh350.00 paid to NAIVAS LIMITED. on 9/9/26 at 1:00 PM.New M-PESA balance is Ksh684.56. Transaction cost, Ksh0.00. Amount you can transact within the day is 499,150.00.")!!
        assertEquals("buy_goods", t.type)
        assertEquals(350.0, t.amount!!, 0.001)
        assertEquals("NAIVAS LIMITED", t.counterparty)
        assertEquals("business", t.counterpartyKind)
        assertEquals(0.0, t.cost!!, 0.001)
    }

    @Test
    fun receiveFromPerson() {
        val t = mpesa("SFA1B2C3D9 Confirmed.You have received Ksh1,000.00 from JANE DOE 0711123456 on 9/9/26 at 11:00 AM  New M-PESA balance is Ksh2,234.56. Separate personal and business funds through Pochi la Biashara on *334#.")!!
        assertEquals("receive", t.type)
        assertEquals("in", t.direction)
        assertEquals(1000.0, t.amount!!, 0.001)
        assertEquals("JANE DOE", t.counterparty)
        assertEquals("254711123456", t.counterpartyPhone)
        assertNull(t.cost)
    }

    @Test
    fun receiveWithTrailingDotKeepsThePhone() {
        val t = mpesa("SFA1B2C3D9 Confirmed.You have received Ksh1,000.00 from JANE DOE 0711123456. on 9/9/26 at 11:00 AM New M-PESA balance is Ksh2,234.56.")!!
        assertEquals("254711123456", t.counterpartyPhone)
        assertEquals("JANE DOE", t.counterparty)
        assertEquals("person", t.counterpartyKind)
    }

    @Test
    fun receiveFromBusiness() {
        val t = mpesa("SFA1B2C3E0 Confirmed.You have received Ksh5,000.00 from EQUITY BANK KENYA LIMITED 300300 on 9/9/26 at 9:00 AM New M-PESA balance is Ksh7,234.56.")!!
        assertEquals("receive", t.type)
        assertEquals("business", t.counterpartyKind)
        assertNull(t.counterpartyPhone)
    }

    @Test
    fun withdrawAtAgent() {
        val t = mpesa("SFA1B2C3E1 Confirmed.on 9/9/26 at 2:00 PMWithdraw Ksh500.00 from 123456 - JOHN AGENT SHOP New M-PESA balance is Ksh184.56. Transaction cost, Ksh29.00. Amount you can transact within the day is 498,650.00.")!!
        assertEquals("withdraw", t.type)
        assertEquals("out", t.direction)
        assertEquals(500.0, t.amount!!, 0.001)
        assertEquals(29.0, t.cost!!, 0.001)
        assertEquals("JOHN AGENT SHOP", t.counterparty)
        assertEquals("agent", t.counterpartyKind)
    }

    @Test
    fun depositAtAgent() {
        val t = mpesa("SFA1B2C3E2 Confirmed.on 9/9/26 at 3:00 PMGive Ksh1,000.00 cash to JOHN AGENT SHOP New M-PESA balance is Ksh1,184.56.")!!
        assertEquals("deposit", t.type)
        assertEquals("in", t.direction)
        assertEquals("JOHN AGENT SHOP", t.counterparty)
        assertEquals("agent", t.counterpartyKind)
    }

    @Test
    fun ownAirtime() {
        val t = mpesa("SFA1B2C3E3 confirmed.You bought Ksh50.00 of airtime on 9/9/26 at 4:00 PM.New M-PESA balance is Ksh1,134.56. Transaction cost, Ksh0.00.")!!
        assertEquals("airtime", t.type)
        assertEquals("out", t.direction)
        assertEquals("self", t.counterpartyKind)
        assertEquals("OWN AIRTIME", t.counterparty)
        assertEquals(50.0, t.amount!!, 0.001)
    }

    @Test
    fun airtimeForAnotherNumber() {
        val t = mpesa("SFA1B2C3E4 confirmed.You bought Ksh100.00 of airtime for 0722123456 on 9/9/26 at 4:10 PM.New M-PESA balance is Ksh1,034.56. Transaction cost, Ksh0.00.")!!
        assertEquals("airtime", t.type)
        assertEquals("person", t.counterpartyKind)
        assertEquals("254722123456", t.counterpartyPhone)
    }

    @Test
    fun fulizaDrawOldWording() {
        val t = mpesa("RK94V6J95E Confirmed. Fuliza M-PESA amount is Ksh 90.00. Interest charged Ksh 0.90. Total Fuliza M-PESA outstanding amount is Ksh 233.72 due on 08/12/23. To check daily charges, Dial *234*0#OK Select Query Charges")!!
        assertEquals("fuliza_draw", t.type)
        assertEquals("other", t.direction)
        assertEquals(90.0, t.amount!!, 0.001)
        assertEquals("RK94V6J95E", t.ref)
        assertEquals("FULIZA", t.counterparty)
        assertEquals("self", t.counterpartyKind)
    }

    @Test
    fun fulizaDrawNewWording() {
        val t = mpesa("FLZ1AAAAA1 Confirmed. You have used Fuliza M-PESA. Amount used Ksh500.00 on 20/7/26 at 8:20 PM. Access fee charged Ksh10.00. Total Fuliza M-PESA outstanding is Ksh510.00. New M-PESA balance is Ksh0.00.")!!
        assertEquals("fuliza_draw", t.type)
        assertEquals(500.0, t.amount!!, 0.001)
    }

    @Test
    fun fulizaRepayment() {
        val t = mpesa("SD38YVVQ1A Confirmed. Ksh 500.00 from your M-PESA has been used to partially pay your Fuliza M-PESA. Outstanding Fuliza M-PESA is Ksh 957.83 due on 03/05/24.")!!
        assertEquals("fuliza_repay", t.type)
        assertEquals(500.0, t.amount!!, 0.001)
        assertEquals(false, t.fulizaCleared)
    }

    // ── Real September 2026 wording (photographed on a study phone; names and numbers replaced) ──

    @Test
    fun realFulizaDrawAndPartialRepayment() {
        val draw = mpesa("UIHRL6MLHM Confirmed. Fuliza M-PESA amount is Ksh 60.00. Access Fee charged Ksh 0.60. Total Fuliza M-PESA outstanding amount is Ksh448.14 due on 13/10/26. To check daily charges, Dial *334#OK Select Query Charges")!!
        assertEquals("fuliza_draw", draw.type)
        assertEquals(60.0, draw.amount!!, 0.001)
        assertEquals(0.60, draw.cost!!, 0.001)   // 1% of the draw
        val repay = mpesa("UIGRL6LT7G Confirmed. Ksh 450.00 from your M-PESA has been used to partially pay your outstanding Fuliza M-PESA. Your available Fuliza M-PESA limit is Ksh 456.90. Your M-PESA balance is 0.00.")!!
        assertEquals("fuliza_repay", repay.type)
        assertEquals(450.0, repay.amount!!, 0.001)
        assertEquals(false, repay.fulizaCleared)
    }

    @Test
    fun realMoneyInFromAirtelMoneyIsMarkedCrossNetwork() {
        // M-Pesa names the wallet it came from and prints the sender's number without its zero.
        val t = mpesa("UIFLY6JZNS Confirmed. You have received Ksh20.00 from AIRTEL MONEY - JANE DOE 100123456 on 15/9/26 at 2:24 PM New M-Pesa balance is Ksh1,234.00. Buy goods with M-Pesa")!!
        assertEquals("receive", t.type)
        assertEquals(20.0, t.amount!!, 0.001)
        assertEquals("airtel", t.counterpartyNetwork)
        assertEquals("person", t.counterpartyKind)
        assertEquals("JANE DOE", t.counterparty)
        assertEquals("254100123456", t.counterpartyPhone)
    }

    @Test
    fun realMoneyInFromAPersonHasAMaskedNumber() {
        // M-Pesa hides the middle of the sender's number; the prefix still names the operator.
        val t = mpesa("UINRL7DIVV Confirmed.You have received Ksh5.00 from jane  doe 0720***123 on 23/9/26 at 9:30 AM  New M-PESA balance is Ksh34,425.00. Invest & earn daily interest with ZIIDI on https://saf.cx/cF6ir")!!
        assertEquals("receive", t.type)
        assertEquals(5.0, t.amount!!, 0.001)
        assertEquals("person", t.counterpartyKind)
        assertEquals("safaricom", t.counterpartyNetwork)
        assertEquals("JANE DOE", t.counterparty)
        assertNull(t.counterpartyPhone)          // too little of it to dial, so none is kept
        assertNotNull(t.counterpartyHash)        // hashed on the name
    }

    @Test
    fun moneyOutAndMoneyInWithOnePersonShareALinkHash() {
        // The send prints the whole number, the receipt only "0720***123": same person, one link.
        val out = mpesa("SFA1B2C3D5 Confirmed. Ksh500.00 sent to JANE DOE 0720456123 on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh10.00. Transaction cost, Ksh7.00.")!!
        val back = mpesa("UINRL7DIVX Confirmed.You have received Ksh200.00 from JANE DOE 0720***123 on 23/9/26 at 9:30 AM New M-PESA balance is Ksh210.00.")!!
        assertNotNull(out.counterpartyLinkHash)
        assertEquals(out.counterpartyLinkHash, back.counterpartyLinkHash)
        // The counterparty hashes still differ: one is keyed on the number, the other on the name.
        assertNotEquals(out.counterpartyHash, back.counterpartyHash)
        // A different number with the same visible digits would collide; a different one does not.
        val other = mpesa("SFA1B2C3D6 Confirmed. Ksh500.00 sent to JOHN DOE 0733456123 on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh10.00. Transaction cost, Ksh7.00.")!!
        assertNotEquals(out.counterpartyLinkHash, other.counterpartyLinkHash)
    }

    @Test
    fun aCounterpartyWithNoNumberHasNoLinkHash() {
        val till = mpesa("SFA1B2C3D7 Confirmed. Ksh100.00 paid to NAIVAS LIMITED. on 9/9/26 at 1:00 PM. New M-PESA balance is Ksh1.00. Transaction cost, Ksh0.00.")!!
        assertNull(till.counterpartyLinkHash)
        assertNotNull(till.counterpartyHash)
    }

    @Test
    fun aMaskedAirtelNumberNamesAirtel() {
        val t = mpesa("UINRL7DIVW Confirmed.You have received Ksh50.00 from JOHN DOE 0733***456 on 23/9/26 at 9:35 AM New M-PESA balance is Ksh100.00.")!!
        assertEquals("airtel", t.counterpartyNetwork)
        assertEquals("person", t.counterpartyKind)
    }

    @Test
    fun realMoneyInFromABankAppStaysABusiness() {
        val t = mpesa("UIGRL6LYLB Confirmed. You have received Ksh450.00 from IM BANK LIMITED- APP on 16/9/26 at 8:29 PM. New M-PESA balance is Ksh450.00. Buy goods with M-PESA.")!!
        assertEquals("receive", t.type)
        assertEquals(450.0, t.amount!!, 0.001)
        assertEquals("business", t.counterpartyKind)
        assertEquals("unknown", t.counterpartyNetwork)
        assertNull(t.counterpartyPhone)
    }

    @Test
    fun realSendToANameWithoutANumber() {
        val t = mpesa("UIHRL6MR9Q Confirmed. Ksh40.00 sent to JANE DOE on 17/9/26 at 7:50 AM. New M-PESA balance is Ksh0.00. Transaction cost, Ksh0.00. Amount you can transact within the day is 499,910.00.")!!
        assertEquals("send", t.type)
        assertEquals(40.0, t.amount!!, 0.001)
        assertEquals(0.0, t.cost!!, 0.001)
        assertEquals("business", t.counterpartyKind)   // no number: a Pochi or till name
        assertNull(t.counterpartyPhone)
    }

    @Test
    fun aNineDigitAccountNumberIsNotReadAsAPhone() {
        // The bare-number form only applies behind the cross-network label.
        val t = mpesa("SFA1B2C3E1 Confirmed. Ksh500.00 sent to SOME SACCO LTD 123456789 on 9/9/26 at 9:00 AM. New M-PESA balance is Ksh10.00. Transaction cost, Ksh7.00.")!!
        assertNull(t.counterpartyPhone)
        assertEquals("unknown", t.counterpartyNetwork)
    }

    @Test
    fun fulizaTextsCarryTheOutstandingFigureButOnlyOnThePhone() {
        val newer = mpesa("FLZ1AAAAA1 Confirmed. You have used Fuliza M-PESA. Amount used Ksh500.00 on 20/7/26 at 8:20 PM. Access fee charged Ksh10.00. Total Fuliza M-PESA outstanding is Ksh510.00. New M-PESA balance is Ksh0.00.")!!
        assertEquals(510.0, newer.fulizaOutstanding!!, 0.001)
        val real = mpesa("UIHRL6MLHM Confirmed. Fuliza M-PESA amount is Ksh 60.00. Access Fee charged Ksh 0.60. Total Fuliza M-PESA outstanding amount is Ksh448.14 due on 13/10/26. To check daily charges, Dial *334#OK Select Query Charges")!!
        assertEquals(448.14, real.fulizaOutstanding!!, 0.001)
        val oldRepay = mpesa("SD38YVVQ1A Confirmed. Ksh 500.00 from your M-PESA has been used to partially pay your Fuliza M-PESA. Outstanding Fuliza M-PESA is Ksh 957.83 due on 03/05/24.")!!
        assertEquals(957.83, oldRepay.fulizaOutstanding!!, 0.001)
        // The 2026 repayment wording prints the available limit, not what is owed.
        val newRepay = mpesa("UIGRL6LT7G Confirmed. Ksh 450.00 from your M-PESA has been used to partially pay your outstanding Fuliza M-PESA. Your available Fuliza M-PESA limit is Ksh 456.90. Your M-PESA balance is 0.00.")!!
        assertNull(newRepay.fulizaOutstanding)
        val send = mpesa("SFA1B2C3D4 Confirmed. Ksh100.00 sent to JANE DOE 0711123456 on 9/9/26 at 10:00 AM. New M-PESA balance is Ksh1,234.56. Transaction cost, Ksh0.00.")!!
        assertNull(send.fulizaOutstanding)
    }

    @Test
    fun fulizaDrawCarriesItsAccessFee() {
        // Older wording calls it interest, newer wording an access fee: either is the draw's fee.
        val old = mpesa("RK94V6J95E Confirmed. Fuliza M-PESA amount is Ksh 90.00. Interest charged Ksh 0.90. Total Fuliza M-PESA outstanding amount is Ksh 233.72 due on 08/12/23. To check daily charges, Dial *234*0#OK Select Query Charges")!!
        assertEquals(0.90, old.cost!!, 0.001)
        val new = mpesa("FLZ1AAAAA1 Confirmed. You have used Fuliza M-PESA. Amount used Ksh500.00 on 20/7/26 at 8:20 PM. Access fee charged Ksh10.00. Total Fuliza M-PESA outstanding is Ksh510.00. New M-PESA balance is Ksh0.00.")!!
        assertEquals(10.0, new.cost!!, 0.001)
        assertNull(new.fulizaCleared)
    }

    @Test
    fun fulizaDrawNeverTakesAPaymentsTransactionCost() {
        val t = mpesa("FLZ1AAAAA2 Confirmed. Fuliza M-PESA amount is Ksh 150.00. Access Fee charged Ksh 1.50. Total Fuliza M-PESA outstanding amount is Ksh 151.50 due on 20/10/26. Transaction cost, Ksh0.00.")!!
        assertEquals("fuliza_draw", t.type)
        assertEquals(1.50, t.cost!!, 0.001)
        // No fee stated at all: unknown, not zero.
        val none = mpesa("FLZ1AAAAA3 Confirmed. Fuliza M-PESA amount is Ksh 150.00. Total Fuliza M-PESA outstanding amount is Ksh 151.50 due on 20/10/26.")!!
        assertNull(none.cost)
    }

    @Test
    fun fulizaRepaymentThatClearsTheBalance() {
        val t = mpesa("SD38YVVQ1B Confirmed. Ksh 151.50 from your M-PESA has been used to fully pay your outstanding Fuliza M-PESA. Available Fuliza M-PESA limit is Ksh 2,500.00. M-PESA balance is Ksh 48.50.")!!
        assertEquals("fuliza_repay", t.type)
        assertEquals(151.50, t.amount!!, 0.001)
        assertEquals(true, t.fulizaCleared)
        // Other rows never carry the flag.
        val send = mpesa("SFA1B2C3D4 Confirmed. Ksh100.00 sent to JANE DOE 0711123456 on 9/9/26 at 10:00 AM. New M-PESA balance is Ksh1,234.56. Transaction cost, Ksh0.00.")!!
        assertNull(send.fulizaCleared)
    }

    @Test
    fun mshwariDepositAndWithdrawal() {
        val d = mpesa("MSW1AAAAA1 Confirmed. Ksh1,000.00 transferred to M-Shwari account on 1/7/26 at 10:00 AM. New M-PESA balance is Ksh200.00.")!!
        assertEquals("savings_deposit", d.type)
        assertEquals("M-SHWARI", d.counterparty)
        val w = mpesa("EB87ST824 Confirmed. You have transferred Ksh50.00 from your M-Shwari account on 13/10/13 at 2:14 AM. M-Shwari balance is Ksh20,037.69. M-PESA balance is Ksh4,315.00.")!!
        assertEquals("savings_withdraw", w.type)
        assertEquals(50.0, w.amount!!, 0.001)
        val k = mpesa("KCB1AAAAA1 Confirmed. Ksh3,000.00 transferred to KCB M-PESA account on 4/6/26 at 2:00 PM. New M-PESA balance is Ksh500.00.")!!
        assertEquals("savings_deposit", k.type)
        assertEquals("KCB M-PESA", k.counterparty)
    }

    @Test
    fun reversalTakesFirstAmountAndTheReversedRef() {
        val t = mpesa("SFA1B2C3E7 Confirmed. Reversal of transaction SFA1B2C3E6 has been successfully reversed on 9/9/26 at 8:00 PM and Ksh500.00 is credited to your M-PESA account. New M-PESA balance is Ksh1,500.00.")!!
        assertEquals("reversal", t.type)
        assertEquals("other", t.direction)
        assertEquals(500.0, t.amount!!, 0.001)
        assertEquals("partial", t.parseStatus)
        assertEquals("SFA1B2C3E7", t.ref)
        assertEquals("SFA1B2C3E6", t.reversedRef)
        val send = mpesa("SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM.")!!
        assertNull(send.reversedRef)
    }

    @Test
    fun bodyTimeIsNairobiTime() {
        val t = mpesa("SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh1,234.56.")!!
        val cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("Africa/Nairobi"))
        cal.clear()
        cal.set(2026, 8, 9, 10, 15, 0)
        assertEquals(cal.timeInMillis, t.bodyTime)
        val pm = mpesa("SFA1B2C3E1 Confirmed.on 9/9/26 at 2:00 PMWithdraw Ksh500.00 from 123456 - JOHN AGENT SHOP New M-PESA balance is Ksh184.56.")!!
        cal.clear()
        cal.set(2026, 8, 9, 14, 0, 0)
        assertEquals(cal.timeInMillis, pm.bodyTime)
        val none = airtel("Trans ID: PP260909.1015.A12345. You have sent KES 500.00 to JOHN DOE 0733123456.")!!
        assertNull(none.bodyTime)
        // Midnight and noon edge cases of the 12-hour clock.
        val midnight = mpesa("SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 12:05 AM.")!!
        cal.clear()
        cal.set(2026, 8, 9, 0, 5, 0)
        assertEquals(cal.timeInMillis, midnight.bodyTime)
    }

    @Test
    fun failuresAreKeptAndBalanceQueriesAreDropped() {
        // A refused payment is kept as its own type, so failed attempts can be counted.
        val failed = mpesa("Failed. You do not have enough money in your M-PESA account to send Ksh500.00 to JOHN DOE. New M-PESA balance is Ksh100.00.")!!
        assertEquals(MobileMoneyParser.TYPE_FAILED, failed.type)
        assertEquals("ok", failed.parseStatus)
        assertNull(failed.amount)
        assertNull(failed.counterparty)
        assertEquals(100.0, failed.balanceAfter!!, 0.001)
        val airtelFailed = airtel("Your transaction has failed. Your Airtel Money balance is Ksh 120. Please try again. TID: AB12CD34EF5")!!
        assertEquals(MobileMoneyParser.TYPE_FAILED, airtelFailed.type)
        assertNull(airtelFailed.amount)
        assertNull(mpesa("SFA1B2C3E5 Confirmed. Your M-PESA balance was Ksh1,234.56 on 9/9/26 at 6:00 PM. Transaction cost, Ksh0.00."))
        assertNull(mpesa("SFA1B2C3E5 Confirmed. Your account balance was: M-PESA Account : Ksh1,234.56 M-Shwari Account : Ksh500.00 KCB M-PESA Account : Ksh0.00 on 9/9/26 at 6:00 PM."))
    }

    @Test
    fun unrecognisedMpesaMessageIsKeptWithoutAmount() {
        val t = mpesa("SFA1B2C3E6 Confirmed. Some new template Ksh999.00 that the parser has never seen on 9/9/26 at 7:00 PM.")!!
        assertEquals("unknown", t.type)
        assertEquals("unknown", t.parseStatus)
        assertNull(t.amount)
    }

    @Test
    fun airtelSendProvisional() {
        val t = airtel("Trans ID: PP260909.1015.A12345. You have sent KES 500.00 to JOHN DOE 0733123456. Charges KES 11.00. Your available balance is KES 1,000.00.")!!
        assertEquals("airtel", t.network)
        assertEquals("send", t.type)
        assertEquals("out", t.direction)
        assertEquals(500.0, t.amount!!, 0.001)
        assertEquals(11.0, t.cost!!, 0.001)
        assertEquals("JOHN DOE", t.counterparty)
        assertEquals("254733123456", t.counterpartyPhone)
        assertEquals("airtel", t.counterpartyNetwork)
        assertEquals("PP260909.1015.A12345", t.ref)
        assertEquals("partial", t.parseStatus)
    }

    @Test
    fun airtelReceiveProvisional() {
        val t = airtel("You have received KES 300.00 from JANE DOE 0733123456. Trans ID: PP260909.1100.B12345. Your available balance is KES 1,300.00.")!!
        assertEquals("receive", t.type)
        assertEquals("in", t.direction)
        assertEquals(300.0, t.amount!!, 0.001)
    }

    @Test
    fun airtelWithdrawalNeedsAConfirmation() {
        val real = airtel("Txn ID: PP260909.1200.C12345. You have withdrawn KES 500.00 from agent 55555 MAMA MBOGA. Charges KES 28.00. Your balance is KES 800.00.")!!
        assertEquals("withdraw", real.type)
        assertEquals(500.0, real.amount!!, 0.001)
        assertNull(airtel("Withdraw KES 5,000 at any Airtel Money agent at no charge this weekend! Dial *334#."))
    }

    @Test
    fun airtelPromotionWithoutAmountOrRefIsDropped() {
        assertNull(airtel("Enjoy double bonus on all deposits this weekend! Dial *334# to get started."))
    }

    @Test
    fun pricedPromotionsWithNoTransactionIdAreDropped() {
        assertNull(airtel("Get KES 50 bonus airtime when you top up today! Dial *100#."))
        assertNull(mpesa("Dear SAMUEL, we are unable to progress your Fuliza M-PESA opt in request. Kindly contact customer care for assistance."))
        assertNull(mpesa("Stand a chance to win Ksh1,000,000! Transact with M-PESA today. T&Cs apply."))
        // An M-Pesa text that says Confirmed but matches no rule is still kept for review.
        assertNotNull(mpesa("SFA1B2C3D4 Confirmed. Your new service has been activated for Ksh50.00."))
    }

    @Test
    fun hashesAreStableWithinASaltAndDifferAcrossSalts() {
        val a = MobileMoneyParser.counterpartyHash("JOHN DOE", "254722123456", salt)
        val b = MobileMoneyParser.counterpartyHash("J DOE", "254722123456", salt)
        val c = MobileMoneyParser.counterpartyHash("JOHN DOE", null, salt)
        val d = MobileMoneyParser.counterpartyHash("JOHN DOE", "254722123456", "another-device")
        assertEquals(a, b)
        assertEquals(20, a!!.length)
        assertNotEquals(a, c)
        assertNotEquals(a, d)
        assertNull(MobileMoneyParser.counterpartyHash(null, null, salt))
        assertEquals(MobileMoneyParser.djb2("abc"), MobileMoneyParser.djb2("abc"))
        assertEquals("254722123456", MobileMoneyParser.normalizePhone("+254 722 123456"))
        assertEquals("254722123456", MobileMoneyParser.normalizePhone("0722123456"))
    }

    @Test
    fun counterpartyNetworkFromPrefix() {
        assertEquals("safaricom", MobileMoneyParser.counterpartyNetwork("254722123456"))
        assertEquals("safaricom", MobileMoneyParser.counterpartyNetwork("254110123456"))
        assertEquals("airtel", MobileMoneyParser.counterpartyNetwork("254733123456"))
        assertEquals("airtel", MobileMoneyParser.counterpartyNetwork("254100123456"))
        assertEquals("telkom", MobileMoneyParser.counterpartyNetwork("254770123456"))
        // The April 2026 plan: Airtel to 0108, Safaricom to 0119 and 0140-0146, plus its August 2026 0180-0182.
        assertEquals("airtel", MobileMoneyParser.counterpartyNetwork("254107123456"))
        assertEquals("safaricom", MobileMoneyParser.counterpartyNetwork("254118123456"))
        assertEquals("safaricom", MobileMoneyParser.counterpartyNetwork("254140123456"))
        assertEquals("safaricom", MobileMoneyParser.counterpartyNetwork("254181123456"))
        assertEquals("other", MobileMoneyParser.counterpartyNetwork("254763123456"))   // Equitel
        assertEquals("other", MobileMoneyParser.counterpartyNetwork("254747123456"))   // Faiba
        assertEquals("unknown", MobileMoneyParser.counterpartyNetwork("254767123456")) // dropped from the plan
        assertEquals("unknown", MobileMoneyParser.counterpartyNetwork("254150123456")) // unassigned
        assertEquals("unknown", MobileMoneyParser.counterpartyNetwork(null))
        assertEquals("unknown", MobileMoneyParser.counterpartyNetwork("12345"))
    }

    // ── Server rules ──

    @Test
    fun remoteRuleTakesPrecedenceOverBuiltIn() {
        val rules = RuleSet.fromRows(
            listOf(
                rule(
                    "network" to "mpesa", "priority" to 1, "transaction_type" to "send", "direction" to "out",
                    "kind" to "auto", "pattern" to """{AMT}\s+sent\s+to\s+(.+?)\s+via\s+Pochi""",
                    "amount_group" to 1, "party_group" to 2,
                )
            ),
            "v1"
        )
        val body = "SFA1B2C3F1 Confirmed. Ksh300.00 sent to JANE MERCHANT via Pochi on 9/9/26 at 5:00 PM. New M-PESA balance is Ksh34.56."
        val builtIn = mpesa(body)!!
        assertEquals("JANE MERCHANT VIA POCHI", builtIn.counterparty)
        val withRule = mpesa(body, rules)!!
        assertEquals("send", withRule.type)
        assertEquals("JANE MERCHANT", withRule.counterparty)
        assertEquals("business", withRule.counterpartyKind)
        assertEquals(300.0, withRule.amount!!, 0.001)
        assertEquals("ok", withRule.parseStatus)
    }

    @Test
    fun remoteRuleRescuesWordingTheGateWouldNotRead() {
        val body = "You have sent KShs500.00 to JOHN DOE 0722000000."
        // No transaction id, but it reads like money moving: kept as an unread text (its template
        // reaches the desk) instead of being dropped, and a server rule reads it on the next re-read.
        val before = airtel(body)!!
        assertEquals(MobileMoneyParser.TYPE_UNKNOWN, before.type)
        assertEquals(MobileMoneyParser.STATUS_UNKNOWN, before.parseStatus)
        val rules = RuleSet.fromRows(
            listOf(
                rule(
                    "network" to "airtel", "transaction_type" to "send", "direction" to "out", "kind" to "auto",
                    "pattern" to """sent\s+{AMT}\s+to\s+(.+?)\.?$""", "amount_group" to 1, "party_group" to 2,
                )
            ),
            "v5"
        )
        val after = airtel(body, rules)!!
        assertEquals("send", after.type)
        assertEquals(500.0, after.amount!!, 0.001)
        assertEquals("254722000000", after.counterpartyPhone)
    }

    @Test
    fun remoteIgnoreRuleKeepsMessageAsIgnored() {
        // Carries an id, so without a rule it is kept as an unread transaction.
        val body = "TID:AB12CD34EF5. Your KES 50 bonus has been credited. Dial *100#."
        assertNotNull(airtel(body))
        val rules = RuleSet.fromRows(
            listOf(rule("network" to "airtel", "priority" to 1, "transaction_type" to "ignore", "pattern" to "bonus")),
            "v2"
        )
        // Kept on the phone as an ignored row (body included, never uploaded while ignored), so a
        // rule written too broadly can be undone by a re-read.
        val ignored = airtel(body, rules)!!
        assertEquals(MobileMoneyParser.TYPE_IGNORED, ignored.type)
        assertEquals(body, ignored.body)
        assertNull(ignored.amount)
    }

    @Test
    fun airtelTextsWithNoIdAreKeptOnlyWhenTheyReadLikeMoney() {
        // An offer, an advert opening and airtime credited to the line move no money: dropped.
        assertNull(airtel("1.5GB for Ksh 50 valid 24hrs. Dial *544# to buy."))
        assertNull(airtel("Withdraw cash at any Airtel Money agent and get Ksh 20 back. Dial *334#."))
        assertNull(airtel("You have received airtime of Ksh 20 at a service fee of Ksh 2. A total of Ksh 22 will be deducted on your next top up."))
        assertNull(airtel("You have received KSH 50 Airtime from 733123456.Balance is KSH 63. Dial *100# for more."))
        // Money moving with no id: kept unread, so the wording reaches the desk.
        val received = airtel("You have received Ksh 1,000.00 from EQUITY BANK on 05/11/26 at 10:15. Your balance is Ksh 1,250.00.")!!
        assertEquals(MobileMoneyParser.TYPE_UNKNOWN, received.type)
        assertEquals(MobileMoneyParser.STATUS_UNKNOWN, received.parseStatus)
    }

    @Test
    fun remoteSenderRuleMustBeAnchored() {
        val body = "Trans ID: PP1.A1. You have sent KES 100.00 to JOHN DOE 0733123456. Charges KES 0.00."
        assertNull(MobileMoneyParser.parse(body, "AMONEY", ts, RuleSet.EMPTY, salt))
        val anchored = RuleSet.fromRows(
            listOf(rule("network" to "airtel", "transaction_type" to "sender", "pattern" to "^AMONEY$")),
            "v3"
        )
        val t = MobileMoneyParser.parse(body, "AMONEY", ts, anchored, salt)!!
        assertEquals("airtel", t.network)
        assertEquals("send", t.type)
        val loose = RuleSet.fromRows(
            listOf(rule("network" to "airtel", "transaction_type" to "sender", "pattern" to ".")),
            "v3b"
        )
        assertEquals(1, loose.skipped)
        assertNull(MobileMoneyParser.parse(body, "AMONEY", ts, loose, salt))
    }

    @Test
    fun invalidRemoteRulesAreSkipped() {
        val rules = RuleSet.fromRows(
            listOf(
                rule("network" to "mpesa", "transaction_type" to "send", "pattern" to "("),
                rule("network" to "orange", "transaction_type" to "send", "pattern" to "x"),
                rule("network" to "mpesa", "transaction_type" to "send_money", "pattern" to """{AMT}\s+sent\s+to\s+(.+?)\s+on""", "amount_group" to 1, "party_group" to 2),
                rule("network" to "mpesa", "priority" to 5, "transaction_type" to "deposit", "direction" to "in",
                    "kind" to "agent", "pattern" to """deposited\s+{AMT}\s+at\s+(.+?)\.""", "amount_group" to 1, "party_group" to 2),
            ),
            "v4"
        )
        assertEquals(3, rules.skipped)
        assertEquals(1, rules.rules.size)
        val t = mpesa("SFA1B2C3F2 Confirmed. You deposited Ksh700.00 at 55555 - MAMA MBOGA SHOP. New M-PESA balance is Ksh900.00.", rules)!!
        assertEquals("deposit", t.type)
        assertEquals("MAMA MBOGA SHOP", t.counterparty)
        assertEquals("agent", t.counterpartyKind)
        assertEquals(700.0, t.amount!!, 0.001)
        // The mistyped rule did not shadow the built-in send rule.
        val send = mpesa("SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM.", rules)!!
        assertEquals("send", send.type)
    }

    @Test
    fun rulesSortByPriorityThenId() {
        val rules = RuleSet.fromRows(
            listOf(
                rule("id" to 7, "network" to "mpesa", "priority" to 100, "transaction_type" to "receive", "direction" to "in", "pattern" to "second"),
                rule("id" to 3, "network" to "mpesa", "priority" to 100, "transaction_type" to "send", "direction" to "out", "pattern" to "first"),
                rule("id" to 9, "network" to "mpesa", "priority" to 1, "transaction_type" to "paybill", "direction" to "out", "pattern" to "zero"),
            ),
            "v6"
        )
        assertEquals(listOf(9L, 3L, 7L), rules.rules.map { it.id })
    }
    // ── Operator senders are not captured ──

    @Test
    fun operatorTextsAreNotCaptured() {
        assertNull(MobileMoneyParser.parse("Dial *544# for the best offers this weekend!", "Safaricom", ts, RuleSet.EMPTY, salt))
        assertNull(MobileMoneyParser.parse("Your line has been registered. Thank you for choosing Safaricom.", "Safaricom", ts, RuleSet.EMPTY, salt))
    }

    @Test
    fun mpesaBundlePaymentIsAMoneyRow() {
        // The M-Pesa payment for a bundle is a money row with its amount and receipt.
        val t = mpesa(
            "SFA9Z8Y7X6 Confirmed. Ksh1,000.00 paid to SAFARICOM DATA BUNDLES. " +
                "on 9/9/26 at 10:15 AM. New M-PESA balance is Ksh500.00. Transaction cost, Ksh0.00."
        )!!
        assertEquals("bundle", t.type)
        assertEquals(1000.0, t.amount!!, 0.001)
    }
    // ── Capture gate and message keys ──

    @Test
    fun captureGateAdmitsMoneySendersOnly() {
        assertEquals(false, MobileMoneyParser.isCapturedSender("Safaricom"))
        assertEquals(true, MobileMoneyParser.isCapturedSender("MPESA"))
        assertEquals(true, MobileMoneyParser.isCapturedSender("AirtelMoney"))
        assertEquals(false, MobileMoneyParser.isCapturedSender("+254722123456"))
        assertEquals(false, MobileMoneyParser.isCapturedSender("KCB"))
        assertNull(MobileMoneyParser.networkForSender("Safaricom"))
    }

    @Test
    fun keysDifferWhenOnlyTheReferenceDiffers() {
        val a = "SFAQ000001 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM."
        val b = "SFB2000001 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 9/9/26 at 10:15 AM."
        assertNotEquals(MobileMoneyParser.messageKey(a, ts, true), MobileMoneyParser.messageKey(b, ts, true))
        // The same SMS read live and again from the inbox keys the same, whatever its timestamp source.
        assertEquals(MobileMoneyParser.messageKey(a, ts, true), MobileMoneyParser.messageKey(a, ts + 5_000, true))
    }

    @Test
    fun textsWithoutACodeAreKeyedByTheMinute() {
        val text = "Confirmed. Something without a code happened at the counter."
        assertNotEquals(MobileMoneyParser.messageKey(text, ts, false), MobileMoneyParser.messageKey(text, ts + 3 * 60_000, false))
        // The live receiver and the inbox scan read one SMS within the same minute: one key.
        assertEquals(MobileMoneyParser.messageKey(text, ts, false), MobileMoneyParser.messageKey(text, ts + 5_000, false))
    }
    @Test
    fun numbersAreMaskedStatementStyleBeforeUpload() {
        assertEquals(
            "sent to JOHN DOE 0722***456 and 254733***789, ref +254711***321, account 12345678",
            MobileMoneyParser.maskNumbers("sent to JOHN DOE 0722123456 and 254733456789, ref +254711000321, account 12345678")
        )
    }
    // ── Airtel Money, the phrasings documented publicly (still provisional until a real sample) ──

    @Test
    fun airtelSendWithoutHaveAndBracketedNumber() {
        val t = airtel("Airtel Money: You sent KES 1,500.00 to JOHN DOE (0712345678) on 15/12/2024 at 2:30 PM. Your new balance is KES 8,500.00. Transaction ID: AIR123456789. Charges: KES 15.00.")!!
        assertEquals("send", t.type)
        assertEquals(1500.0, t.amount!!, 0.001)
        assertEquals("254712345678", t.counterpartyPhone)
        assertEquals("JOHN DOE", t.counterparty)
        assertEquals(15.0, t.cost!!, 0.001)
        assertEquals("AIR123456789", t.ref)
        assertEquals(MobileMoneyParser.STATUS_PARTIAL, t.parseStatus)
    }

    @Test
    fun airtelReceiveWithoutHave() {
        val t = airtel("Airtel Money: You received KES 300.00 from JANE SMITH (0723456789) on 15/12/2024 at 2:30 PM. Your new balance is KES 3,200.00. Transaction ID: AIR123456790.")!!
        assertEquals("receive", t.type)
        assertEquals(300.0, t.amount!!, 0.001)
        assertEquals("254723456789", t.counterpartyPhone)
        assertEquals("JANE SMITH", t.counterparty)
    }

    @Test
    fun airtelAmountFirstMerchantPayment() {
        val t = airtel("Airtel Money: KES 5,000.00 paid to MERCHANT NAME (0721111111) on 15/12/2024 at 2:30 PM. Your new balance is KES 3,500.00. Transaction ID: AIR123456791.")!!
        assertEquals("buy_goods", t.type)   // no account number: a till, not a paybill
        assertEquals(5000.0, t.amount!!, 0.001)
        assertEquals("MERCHANT NAME", t.counterparty)
        assertEquals(MobileMoneyParser.KIND_BUSINESS, t.counterpartyKind)
    }

    @Test
    fun airtelSwahiliSend() {
        val t = airtel("Umetuma KES 200.00 kwa PETER OTIENO 0733123456. Trans ID: PP260909.1015.A12345. Salio lako ni KES 800.00.")!!
        assertEquals("send", t.type)
        assertEquals(200.0, t.amount!!, 0.001)
        assertEquals("254733123456", t.counterpartyPhone)
    }
    // ── Airtel Money, real 2026 wording from a study phone (names, numbers and balances replaced) ──

    @Test
    fun airtelRealReceiveVerbFirstWithTid() {
        val t = airtel("TID:93RAIRQ77FS. Received Ksh 10 from JANE DOE 254722000111 on 16/09/26 11:31 AM. Bal:Ksh 190.0 Sender TID:UIG4R73WQV.")!!
        assertEquals("receive", t.type)
        assertEquals(10.0, t.amount!!, 0.001)
        assertEquals("JANE DOE", t.counterparty)
        assertEquals("254722000111", t.counterpartyPhone)
        assertEquals("safaricom", t.counterpartyNetwork)
        assertEquals("93RAIRQ77FS", t.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, t.parseStatus)
        assertNotNull(t.bodyTime)
    }

    @Test
    fun airtelRealSendAmountFirstWithLeadingId() {
        val t = airtel("P3RAIQM1E4N. Ksh 20 sent to JANE DOE 254722000111 on 16/09/26 11:20 AM. Bal:Ksh 170.0")!!
        assertEquals("send", t.type)
        assertEquals(20.0, t.amount!!, 0.001)
        assertEquals("254722000111", t.counterpartyPhone)
        assertEquals("P3RAIQM1E4N", t.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, t.parseStatus)
    }

    @Test
    fun airtelRealReceiveAmountFirstWithLeadingId() {
        val t = airtel("U3RAGOD5ZEU. Ksh 200 received from JOHN DOE 254733000222 on 15/09/26 10:28 AM. Bal:Ksh 390.0")!!
        assertEquals("receive", t.type)
        assertEquals(200.0, t.amount!!, 0.001)
        assertEquals("U3RAGOD5ZEU", t.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, t.parseStatus)
    }

    @Test
    fun airtelRealBankReceiptStaysProvisional() {
        val t = airtel("73QORXT6I5J. You have received Ksh 15 from KCB Bank on 01/09/26 09:34 AM. Bal:Ksh 15.0")!!
        assertEquals("receive", t.type)
        assertEquals(15.0, t.amount!!, 0.001)
        assertEquals("73QORXT6I5J", t.ref)
        assertEquals(MobileMoneyParser.KIND_BUSINESS, t.counterpartyKind)
    }

    @Test
    fun airtelRealBundlePurchaseWithNumericId() {
        val t = airtel("07912702081 Confirmed. Bundle purchase of Ksh 15 from Airtel Networks Kenya Ltd on 01/09/26 09:35 AM. Bal:Ksh 0.0")!!
        assertEquals("bundle", t.type)
        assertEquals(15.0, t.amount!!, 0.001)
        assertEquals("07912702081", t.ref)
    }

    @Test
    fun airtelMiniStatementIsNotATransaction() {
        assertNull(airtel("LAST 5 TXN: 1. 2026-09-01 09:35:27 : 07912702081 : 15 Ksh : BUNSLF 127672 : Airtel Networks Kenya Ltd 2. 2026-09-01 09:34:59 : 73QORXT6I5J : 15 Ksh : SUBSB2WNTB KCB0000 : KCB Bank"))
    }

    // ── Airtel Money, second batch of real 2026 wording (2026-09-16; identifying details replaced) ──

    @Test
    fun airtelRealBundlePurchaseInFull() {
        val t = airtel("77687700737 Confirmed. Bundle purchase successful of Ksh 15 via Airtel Networks Kenya Ltd on 13/09/26 at 06:17 PM. Bal: Ksh 94.0.")!!
        assertEquals("bundle", t.type)
        assertEquals(15.0, t.amount!!, 0.001)
        assertEquals("77687700737", t.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, t.parseStatus)
        assertNotNull(t.bodyTime)
    }

    @Test
    fun airtelRealPaybillToAnMpesaPaybill() {
        val t = airtel("Y3R43N9LBUN. Ksh 20 paid to CORNER MINI SUPERMARKET   1 account 123456 on 11/09/2026 19:15. Fee Ksh 0. Bal:Ksh 109.0. MPESA ID:UIBS06VLWN")!!
        assertEquals("paybill", t.type)
        assertEquals(20.0, t.amount!!, 0.001)
        assertEquals("CORNER MINI SUPERMARKET 1", t.counterparty)
        assertEquals(MobileMoneyParser.KIND_BUSINESS, t.counterpartyKind)
        assertEquals("safaricom", t.counterpartyNetwork)
        assertEquals(0.0, t.cost!!, 0.001)
        assertEquals("Y3R43N9LBUN", t.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, t.parseStatus)
        assertNotNull(t.bodyTime)   // 24-hour clock, four-digit year, no AM/PM
    }

    @Test
    fun airtelRealTillPaymentGenericText() {
        val t = airtel("73R28LWH136. Ksh 1,400 paid to Lipa Na Mpesa via Airtel Money on 10/09/26 at 10:49 AM. Fee: Ksh 0. Bal: Ksh 833.0. MPESA ID: UIAS06O8OA")!!
        assertEquals("buy_goods", t.type)
        assertEquals(1400.0, t.amount!!, 0.001)
        assertEquals("safaricom", t.counterpartyNetwork)
        assertEquals(true, MobileMoneyParser.GENERIC_PARTY.containsMatchIn(t.counterparty!!))
    }

    @Test
    fun airtelRealBankAgentCashOutIsAWithdrawal() {
        val t = airtel("63RAXZPKKIK Confirmed. Ksh 100 successfully paid to KCB BANK AGENT on 16/09/26 at 07:07 PM. Fee: Ksh 0.0. Bal: Ksh 60.0.")!!
        assertEquals("withdraw", t.type)
        assertEquals(MobileMoneyParser.KIND_AGENT, t.counterpartyKind)
        assertEquals(100.0, t.amount!!, 0.001)
        assertEquals("63RAXZPKKIK", t.ref)
    }

    @Test
    fun airtelRealDeposits() {
        val bank = airtel("E3RAXX528TC. KCB BANK AGENT has deposited Ksh 150 to your account on 16/09/26 07:04 PM. Bal: Ksh 160.0.")!!
        assertEquals("deposit", bank.type)
        assertEquals(150.0, bank.amount!!, 0.001)
        assertEquals("E3RAXX528TC", bank.ref)
        assertEquals(MobileMoneyParser.KIND_AGENT, bank.counterpartyKind)
        val shop = airtel("Corner  General Shop has deposited Ksh 100 to your account on 16/09/26 07:11 PM.Bal: Ksh 160.0. TID V3RAY6227A9.")!!
        assertEquals("deposit", shop.type)
        assertEquals(100.0, shop.amount!!, 0.001)
        assertEquals("V3RAY6227A9", shop.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, shop.parseStatus)
    }

    @Test
    fun airtelRealWithdrawalAtAnAgent() {
        val t = airtel("N3RAY7QBHIP Confirmed. You have withdrawn Ksh 100 from 400604 Corner  General Shop on 16/09/26 at 07:13 PM. Fee: Ksh 11 Bal Ksh 49.0.")!!
        assertEquals("withdraw", t.type)
        assertEquals(100.0, t.amount!!, 0.001)
        assertEquals(11.0, t.cost!!, 0.001)
        assertEquals("N3RAY7QBHIP", t.ref)
        assertEquals(MobileMoneyParser.STATUS_OK, t.parseStatus)
    }

    // ── 1.35: airtime through Safaricom's own paybill, the purchase wording, twin texts ──

    @Test
    fun safaricomAirtimePaybillIsAirtimeNotABundle() {
        val t = mpesa("SFA1B2C3E1 Confirmed. Ksh100.00 sent to SAFARICOM AIRTIME for account 0722123456 on 9/9/26 at 12:30 PM New M-PESA balance is Ksh884.56. Transaction cost, Ksh0.00.")!!
        assertEquals("airtime", t.type)
        assertEquals("out", t.direction)
        assertEquals("business", t.counterpartyKind)
        val offers = mpesa("SFA1B2C3E2 Confirmed. Ksh50.00 sent to SAFARICOM OFFERS for account 0722123456 on 9/9/26 at 12:31 PM New M-PESA balance is Ksh834.56. Transaction cost, Ksh0.00.")!!
        assertEquals("bundle", offers.type)
        val till = mpesa("SFA1B2C3E3 Confirmed. Ksh20.00 paid to SAFARICOM AIRTIME. on 9/9/26 at 1:00 PM.New M-PESA balance is Ksh814.56.")!!
        assertEquals("airtime", till.type)
        val data = mpesa("SFA1B2C3E4 Confirmed. Ksh50.00 sent to SAFARICOM DATA BUNDLES for account 0722123456 on 9/9/26 at 12:30 PM New M-PESA balance is Ksh984.56.")!!
        assertEquals("bundle", data.type)
    }

    @Test
    fun airtelReferenceNeedsADigit() {
        // "reference number ... pending" is prose; without an id the text is not a transaction.
        assertNull(airtel("Your reference number is PENDING for the query you raised. Airtel Money."))
        val t = airtel("TID:93RAIRQ77FS. Received Ksh 10 from JANE DOE 254733123456 on 16/09/26 11:31 AM. Bal:Ksh 190.0 Sender TID:UIG4R73WQV.")!!
        assertEquals("93RAIRQ77FS", t.ref)
        assertEquals("receive", t.type)
    }

    @Test
    fun namedTwinWinsWhicheverArrivedFirst() {
        assertEquals(true, MobileMoneyParser.namedTwinWins("Lipa Na Mpesa via Airtel Money", "CORNER MINI SUPERMARKET 1"))
        assertEquals(false, MobileMoneyParser.namedTwinWins("CORNER MINI SUPERMARKET 1", "Lipa Na Mpesa via Airtel Money"))
        assertEquals(false, MobileMoneyParser.namedTwinWins("Lipa Na Mpesa via Airtel Money", "Lipa Na Mpesa via Airtel Money"))
        assertEquals(false, MobileMoneyParser.namedTwinWins("CORNER MINI SUPERMARKET 1", "CORNER MINI SUPERMARKET 1"))
        assertEquals(true, MobileMoneyParser.namedTwinWins(null, "CORNER MINI SUPERMARKET 1"))
    }

    // ── 1.36: nothing about bundles is dropped ──

}
