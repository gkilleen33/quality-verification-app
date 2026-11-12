package com.qualityverifier.mpesa

import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import java.time.Instant
import java.time.ZoneId

/** A transaction with what the fundi said about it. */
data class MpesaEntry(
    val record: MpesaRecord,
    val category: MpesaCategory? = null,
    val pieceId: String? = null,
    val comment: String? = null,
)

/** How many transactions, and how much money, in shillings. */
data class MoneyTotal(val count: Int, val amountKes: Double) {
    companion object {
        val ZERO = MoneyTotal(0, 0.0)
    }
}

/** Money in and money out over one period. Money that stayed the fundi's own is in neither. */
data class MpesaTotals(val moneyIn: MoneyTotal, val moneyOut: MoneyTotal)

/**
 * The sums the Finances tab and a piece's card show (Grady's description, Slack,
 * 11 November 2026). Plain Kotlin so each has a test.
 */
object MpesaSums {

    private val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

    /**
     * Money in and money out among [entries] that occurred at or after [since] (all of
     * them when null). Amounts only: the fee is M-Pesa's, not the fundi's spending.
     * Fuliza, savings moves and reversals ([MpesaDirection.OTHER]) count in neither.
     */
    fun totals(entries: List<MpesaEntry>, since: Long? = null): MpesaTotals {
        val inPeriod = entries.filter { since == null || it.record.occurredAt >= since }
        fun total(direction: MpesaDirection) = inPeriod.filter { it.record.direction == direction }
            .let { MoneyTotal(it.size, it.sumOf { entry -> entry.record.amountKes }) }
        return MpesaTotals(moneyIn = total(MpesaDirection.IN), moneyOut = total(MpesaDirection.OUT))
    }

    /** The first moment of [now]'s calendar month in Nairobi, epoch millis. */
    fun monthStart(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(NAIROBI).toLocalDate().withDayOfMonth(1)
            .atStartOfDay(NAIROBI).toInstant().toEpochMilli()

    /**
     * What was spent on each piece, by category: money out assigned to the piece, summed per
     * category, with null for money assigned but not yet given a category. Pieces with no
     * money out are absent.
     */
    fun expensesByPiece(entries: List<MpesaEntry>): Map<String, Map<MpesaCategory?, Double>> =
        entries.filter { it.pieceId != null && it.record.direction == MpesaDirection.OUT }
            .groupBy { it.pieceId!! }
            .mapValues { (_, spent) ->
                spent.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.record.amountKes } }
            }
}
