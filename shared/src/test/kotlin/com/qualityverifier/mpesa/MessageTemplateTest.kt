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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The template is the only wording that leaves a participant's phone, so these tests are the
 * privacy guarantee: no name, no number, no amount, no balance, no transaction code.
 */
class MessageTemplateTest {

    @Test
    fun mpesaSendKeepsTheBoilerplateOnly() {
        val text = "UIB5XK2P9Q Confirmed. Ksh1,400.00 sent to JANE WANJIRU 0722123456 on 16/9/26 at 7:15 PM. " +
            "New M-PESA balance is Ksh2,350.50. Transaction cost, Ksh23.00."
        assertEquals(
            "[code10] Confirmed. Ksh # sent to X [num10] on #/#/# at #:# PM. " +
                "New M-PESA balance is Ksh #. Transaction cost, Ksh #.",
            MessageTemplate.of(text)
        )
    }

    @Test
    fun aFailedPaymentKeepsItsReasonButNotTheName() {
        // The reason is the point of keeping a failure, and the words that carry it are listed.
        assertEquals(
            "Failed. You do not have enough money in your M-PESA account to send Ksh # to X. New M-PESA balance is Ksh #.",
            MessageTemplate.of("Failed. You do not have enough money in your M-PESA account to send Ksh500.00 to JOHN DOE. New M-PESA balance is Ksh100.00.")
        )
        assertEquals(
            "Your transaction has failed. Your Airtel Money balance is Ksh #. Please try again. TID: [code11]",
            MessageTemplate.of("Your transaction has failed. Your Airtel Money balance is Ksh 120. Please try again. TID: AB12CD34EF5")
        )
    }

    @Test
    fun airtelAirtimeShowsItsShape() {
        val text = "77687700737 Confirmed. Airtime of Ksh 30 bought for 0733123456 on 15/09/26 at 11:02 AM. Bal: Ksh 12.0."
        assertEquals(
            "[num11] Confirmed. Airtime of Ksh # bought for [num10] on #/#/# at #:# AM. Bal: Ksh #.",
            MessageTemplate.of(text)
        )
    }

    @Test
    fun businessNameAccountAndCrossNetworkCode() {
        val text = "Y3R43N9LBUN. Ksh 20 paid to BLESSED GENERAL MINI SUPERMARKET   1 account 536917 on 11/09/2026 19:15. " +
            "Fee Ksh 0. Bal:Ksh 109.0. MPESA ID:UIBS06VLWN"
        assertEquals(
            "[code11]. Ksh # paid to X MINI X # account [num6] on #/#/# #:#. Fee Ksh #. Bal:Ksh #. MPESA ID:[code10]",
            MessageTemplate.of(text)
        )
    }

    @Test
    fun aNameWithInitialsAndHyphensCollapsesToOneMarker() {
        assertEquals(
            "Ksh # sent to X [num12]",
            MessageTemplate.of("Ksh 500 sent to MARY-ANNE W. OTIENO 254712345678")
        )
    }

    @Test
    fun wordsThatAreAlsoNamesNeverTravel() {
        assertEquals("received from X", MessageTemplate.of("received from GRACE FAITH"))
        assertEquals("received from X", MessageTemplate.of("received from Said Leo Max"))
        assertEquals("Dear X, you have received", MessageTemplate.of("Dear Mercy, you have received"))
    }

    @Test
    fun anAmountOrBalanceNeverShowsItsSize() {
        assertEquals("Bal: Ksh #", MessageTemplate.of("Bal: Ksh 150000"))
        assertEquals("balance is KES #.", MessageTemplate.of("balance is KES1500000.00."))
    }

    @Test
    fun unitsGluedToNumbersStayReadable() {
        assertEquals(
            "You have bought #MB valid for #hrs",
            MessageTemplate.of("You have bought 500MB valid for 24hrs")
        )
        assertEquals("Airtime top-up of Ksh #", MessageTemplate.of("Airtime top-up of Ksh 50"))
    }

    @Test
    fun swahiliWording() {
        val text = "UIB5XK2P9Q Imethibitishwa. Umepokea Ksh500.00 kutoka kwa JOHN KAMAU 0722000000 tarehe 16/9/26 " +
            "saa 7:15 PM. Salio lako jipya la M-PESA ni Ksh1,200.00."
        assertEquals(
            "[code10] Imethibitishwa. Umepokea Ksh # kutoka kwa X [num10] tarehe #/#/# saa #:# PM. " +
                "Salio lako jipya la M-PESA ni Ksh #.",
            MessageTemplate.of(text)
        )
    }

    @Test
    fun neverThreeDigitsInARowWhateverComesIn() {
        val inputs = listOf(
            "1".repeat(150),
            "Ksh 123456789 to 0722123456 ref ABC123DEF456 acc 99887766554433221100",
            "Pay 254722000111/0733000222 (+254711000333) on 2026-09-17T09:05:00.123Z",
            "ID 12345678 PIN 279514 https://example.com/r/8675309?x=1234567",
        )
        val threeDigits = Regex("""[0-9]{3,}""")
        for (input in inputs) {
            val out = MessageTemplate.of(input)
            assertFalse("digits survived in: $out", threeDigits.containsMatchIn(out))
        }
    }

    @Test
    fun longTextIsCappedAndBlankIsEmpty() {
        assertTrue(MessageTemplate.of("confirmed ".repeat(200)).length <= 400)
        assertEquals("", MessageTemplate.of("   "))
    }
}
