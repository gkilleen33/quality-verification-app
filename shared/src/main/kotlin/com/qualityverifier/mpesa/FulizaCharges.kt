// Ported from Drew Burd's money-tracking-kenya (https://github.com/drewburd4/money-tracking-kenya),
// commit 5355a31: the file of the same name in android/app/src/main/java/com/utafiti/sms/.
// Changed only in its package. Part of his parser, ported with it and its tests; nothing in
// Fundi Bora v0.5.0 calls it yet (no message text leaves the phone, and Fuliza charges are not
// shown). Used under the MIT licence below, which the licence requires to travel with the file.
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

import java.util.TimeZone

/**
 * Fuliza's daily charges, worked out on the phone from the outstanding figures the texts print,
 * so that the charge is uploaded and the figure never is (the balance stays on the phone).
 *
 * Every draw text prints the outstanding amount after the draw, and some repayment texts print
 * it too. Between two such figures the balance moved by the draws (with their access fees) and
 * the repayments in between, and whatever is left over is the daily charges Safaricom added at
 * midnight:
 *
 *     charges = outstanding_now - outstanding_before - sum(draws + access fees) + sum(repayments)
 *
 * counted over the events after the earlier figure up to and including this one. The result
 * is exact when every Fuliza text in between is on the phone. A missing text shows up as an
 * impossible number: negative, or more than the tariff's top rate (25KSh plus 20% excise) per
 * midnight passed, and the charge is then left null rather than guessed.
 */
object FulizaCharges {
    data class Event(
        val hash: String,
        val occurredAt: Long,
        val isDraw: Boolean,
        val amount: Double?,
        val cost: Double?,
        val outstanding: Double?,
    )

    private const val ACCESS_RATE = 0.01
    private const val MAX_PER_MIDNIGHT = 30.0
    private const val TOLERANCE = 0.05
    private val NAIROBI = TimeZone.getTimeZone("Africa/Nairobi")

    /** Charges since the previous printed figure, keyed by message hash; only events that print a figure and have an earlier one appear. */
    fun derive(events: List<Event>): Map<String, Double?> {
        val out = mutableMapOf<String, Double?>()
        var anchor: Event? = null
        var drawsSince = 0.0
        var repaysSince = 0.0
        for (e in events.sortedWith(compareBy({ it.occurredAt }, { it.hash }))) {
            val amount = e.amount ?: 0.0
            val draw = if (e.isDraw) amount + (e.cost ?: round2(amount * ACCESS_RATE)) else 0.0
            val repay = if (e.isDraw) 0.0 else amount
            if (e.outstanding == null) {
                drawsSince += draw
                repaysSince += repay
                continue
            }
            val before = anchor
            if (before != null) {
                val charges = e.outstanding - before.outstanding!! - (drawsSince + draw) + (repaysSince + repay)
                val ceiling = midnightsBetween(before.occurredAt, e.occurredAt) * MAX_PER_MIDNIGHT + TOLERANCE
                out[e.hash] = if (charges >= -TOLERANCE && charges <= ceiling) round2(maxOf(0.0, charges)) else null
            }
            anchor = e
            drawsSince = 0.0
            repaysSince = 0.0
        }
        return out
    }

    /** Nairobi midnights passed between two instants (the tariff charges at midnight). */
    fun midnightsBetween(fromMs: Long, toMs: Long): Int {
        fun day(ms: Long) = Math.floorDiv(ms + NAIROBI.getOffset(ms), 86_400_000L)
        return maxOf(0L, day(toMs) - day(fromMs)).toInt()
    }

    private fun round2(v: Double): Double = Math.round(v * 100.0) / 100.0
}
