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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FulizaChargesTest {
    // 10:00 Nairobi on 20 September 2026; each further day is one midnight later.
    private val t0 = 1_789_887_600_000L
    private val day = 86_400_000L

    private fun draw(hash: String, at: Long, amount: Double, outstanding: Double?, cost: Double? = null) =
        FulizaCharges.Event(hash, at, true, amount, cost ?: Math.round(amount) / 100.0, outstanding)

    private fun repay(hash: String, at: Long, amount: Double, outstanding: Double? = null) =
        FulizaCharges.Event(hash, at, false, amount, null, outstanding)

    @Test
    fun chargesAreWhatTheBalanceMovedBeyondDrawsAndRepayments() {
        // Draw 100 (fee 1): owes 101. Next day draw 50 (fee 0.50): 3KSh was added at midnight.
        val events = listOf(
            draw("a", t0, 100.0, 101.0),
            draw("b", t0 + day, 50.0, 154.5),
        )
        val out = FulizaCharges.derive(events)
        assertFalse(out.containsKey("a"))          // nothing earlier to compare with
        assertEquals(3.0, out["b"]!!, 0.001)
    }

    @Test
    fun repaymentsWithoutAFigureAreNettedOut() {
        val events = listOf(
            draw("a", t0, 500.0, 505.0),
            repay("r", t0 + day / 2, 200.0),                    // the 2026 wording prints no figure
            draw("b", t0 + 2 * day, 100.0, 412.0),              // 505 + 6 - 200 + 101
        )
        assertEquals(6.0, FulizaCharges.derive(events)["b"]!!, 0.001)
    }

    @Test
    fun aRepaymentThatPrintsAFigureIsAnAnchorAndATarget() {
        val events = listOf(
            draw("a", t0, 300.0, 303.0),
            repay("r", t0 + day, 100.0, 206.0),                 // 303 + 3 - 100
            draw("b", t0 + 2 * day, 50.0, 259.5),               // 206 + 3 + 50.5
        )
        val out = FulizaCharges.derive(events)
        assertEquals(3.0, out["r"]!!, 0.001)
        assertEquals(3.0, out["b"]!!, 0.001)
    }

    @Test
    fun aClearedBalanceStillAddsUp() {
        // The 3KSh charged at the first midnight is what the interval cost, even though the
        // balance was then paid off and nothing accrued for two more days.
        val events = listOf(
            draw("a", t0, 200.0, 202.0),
            repay("r", t0 + day, 205.0),                        // 202 + 3 at midnight, paid in full
            draw("b", t0 + 3 * day, 80.0, 80.8),                // nothing owed in between
        )
        assertEquals(3.0, FulizaCharges.derive(events)["b"]!!, 0.001)
    }

    @Test
    fun anImpossibleMoveMeansAMissingTextAndIsLeftNull() {
        // Same day, so no midnight passed, yet the balance grew by 100 beyond the draw.
        val jump = listOf(draw("a", t0, 100.0, 101.0), draw("b", t0 + 3_600_000L, 50.0, 251.5))
        val out = FulizaCharges.derive(jump)
        assertTrue(out.containsKey("b"))
        assertNull(out["b"])
        // Negative is impossible too (a repayment text missing).
        val drop = listOf(draw("a", t0, 100.0, 101.0), draw("b", t0 + day, 50.0, 60.5))
        assertNull(FulizaCharges.derive(drop)["b"])
    }

    @Test
    fun theCeilingScalesWithMidnightsPassed() {
        // 30KSh per midnight is the top band with excise; four midnights allow 120.
        val ok = listOf(draw("a", t0, 3000.0, 3030.0), draw("b", t0 + 4 * day, 100.0, 3251.0))
        assertEquals(120.0, FulizaCharges.derive(ok)["b"]!!, 0.001)
        val tooMuch = listOf(draw("a", t0, 3000.0, 3030.0), draw("b", t0 + 4 * day, 100.0, 3252.0))
        assertNull(FulizaCharges.derive(tooMuch)["b"])
    }

    @Test
    fun midnightsCountNairobiDays() {
        assertEquals(0, FulizaCharges.midnightsBetween(t0, t0 + 3_600_000L))
        assertEquals(1, FulizaCharges.midnightsBetween(t0, t0 + day))
        // 23:30 to 00:30 Nairobi is one midnight even though only an hour passed.
        val lateEvening = t0 + 13 * 3_600_000L + 30 * 60_000L
        assertEquals(1, FulizaCharges.midnightsBetween(lateEvening, lateEvening + 3_600_000L))
    }
}
