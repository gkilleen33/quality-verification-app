package com.qualityverifier.mpesa

import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One M-Pesa transaction as Fundi Bora keeps it on the phone and sends it to the server.
 *
 * The counterparty's name and number stay here in the clear: they are on the phone already,
 * in the message itself, and the fundi needs them to recognise a payment. When sending is
 * on they go to the server over HTTPS, which keeps only a code (CounterpartyCoder).
 */
data class MpesaRecord(
    /** The code M-Pesa prints first, "SKA1B2C3D4". Unique, so a second read of one message is no second row. */
    val code: String,
    val direction: MpesaDirection,
    val type: MpesaType,
    val amountKes: Double,
    val feeKes: Double?,
    val balanceAfterKes: Double?,
    /** When the message arrived, epoch millis. */
    val occurredAt: Long,
    val counterpartyName: String?,
    /** "254712345678" when the message prints the number in full; M-Pesa masks it on money in. */
    val counterpartyPhone: String?,
)

/** What one SMS amounts to, by [MpesaIntake.read]. */
sealed interface MpesaReading {
    /** Not from M-Pesa, or from before the agreement. Never stored, never counted. */
    data object Ignored : MpesaReading

    /** From M-Pesa, but no money moved: a balance enquiry, a notice, an advert, a refused payment. */
    data object NotMoney : MpesaReading

    /** From M-Pesa, after the agreement, and the parser could not read it. Counted, never stored. */
    data class Unread(val day: LocalDate) : MpesaReading

    data class Read(val record: MpesaRecord) : MpesaReading
}

/**
 * The rule for which messages Fundi Bora reads (Nathan, 11 November 2026): **only M-Pesa
 * messages received after the fundi agreed**. Everything else is ignored and never stored:
 * other senders, Airtel Money (which the ported parser also reads), and every M-Pesa
 * message from before the agreement.
 *
 * Plain Kotlin so the rule has tests; the live receiver and the inbox scan both call it.
 */
object MpesaIntake {

    /** The parser version recorded with each transaction and each unread count. */
    const val PARSER_VERSION = MobileMoneyParser.BUILTIN_VERSION

    private val NAIROBI: ZoneId = ZoneId.of("Africa/Nairobi")

    /**
     * @param receivedAt when this phone received the message, on this phone's clock: the
     *   clock [agreedAt] was read from, so the two compare.
     * @param occurredAt the time to record, which may be the network's (`date_sent`).
     * @param agreedAt when the fundi agreed to reading; null while reading is off.
     */
    fun read(sender: String?, body: String?, receivedAt: Long, occurredAt: Long, agreedAt: Long?): MpesaReading {
        if (agreedAt == null || receivedAt <= agreedAt) return MpesaReading.Ignored
        if (MobileMoneyParser.networkForSender(sender) != MobileMoneyParser.NETWORK_MPESA) return MpesaReading.Ignored

        // The salt keys the parser's own counterparty hashes, which Fundi Bora never uses:
        // the server codes counterparties with its own key.
        val tx = MobileMoneyParser.parse(body, sender, occurredAt) ?: return MpesaReading.NotMoney
        if (tx.type == MobileMoneyParser.TYPE_FAILED || tx.type == MobileMoneyParser.TYPE_IGNORED) {
            return MpesaReading.NotMoney
        }
        val unread = MpesaReading.Unread(dayOf(occurredAt))
        if (tx.parseStatus == MobileMoneyParser.STATUS_UNKNOWN) return unread
        val code = tx.ref ?: return unread
        val type = MpesaType.fromId(tx.type) ?: return unread
        val direction = MpesaDirection.fromId(tx.direction) ?: return unread
        val amount = tx.amount?.takeIf { it >= 0 } ?: return unread

        // A person, business or agent is somebody; Fuliza, M-Shwari and "own airtime" are
        // the fundi's own products, and coding them would only make a code for a product.
        val somebody = tx.counterpartyKind != MobileMoneyParser.KIND_SELF &&
            tx.counterpartyKind != MobileMoneyParser.KIND_UNKNOWN
        return MpesaReading.Read(
            MpesaRecord(
                code = code,
                direction = direction,
                type = type,
                amountKes = amount,
                feeKes = tx.cost,
                balanceAfterKes = tx.balanceAfter,
                occurredAt = occurredAt,
                counterpartyName = tx.counterparty.takeIf { somebody },
                counterpartyPhone = tx.counterpartyPhone.takeIf { somebody },
            ),
        )
    }

    /** The Nairobi day of [millis], which is the day an unread count is filed under. */
    fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(NAIROBI).toLocalDate()
}
