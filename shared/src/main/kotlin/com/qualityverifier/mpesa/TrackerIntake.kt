package com.qualityverifier.mpesa

import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType

/**
 * One mobile money message as PesaLog found it in a notification: who sent it, its text, and
 * when the phone received it. Built by the listener only for a notification from the default
 * SMS app whose sender is a mobile money sender id; nothing else becomes one of these.
 */
data class NotificationMessage(
    val network: MobileMoneyNetwork,
    /** The sender id or contact name the SMS app showed, "MPESA". */
    val sender: String,
    val text: String,
    /** When the message arrived, on this phone's clock. */
    val receivedAt: Long,
)

/** One transaction PesaLog keeps: the network it came from, and what the parser read. */
data class TrackerRecord(val network: MobileMoneyNetwork, val record: MpesaRecord)

/** What one notification message amounts to, by [TrackerIntake.read]. */
sealed interface TrackerReading {
    /** From before the agreement, or not a mobile money sender. Never stored, never counted. */
    data object Ignored : TrackerReading

    /** A whole mobile money message that moved no money: a notice, an advert, a refused payment. */
    data object NotMoney : TrackerReading

    /**
     * A mobile money message PesaLog could not read: the parser did not know it, or its
     * notification was cut short or hidden. Counted for its Nairobi day, never stored.
     */
    data class Unread(val network: MobileMoneyNetwork, val day: java.time.LocalDate) : TrackerReading

    data class Read(val record: TrackerRecord) : TrackerReading
}

/**
 * PesaLog's rule for which notification messages it reads (build prompt, 13 November 2026),
 * the notification counterpart of [MpesaIntake]: **only mobile money messages that arrived
 * after the person agreed**, from every network [MobileMoneyNetwork] lists, and **only
 * when the notification carried the whole message**.
 *
 * A notification is not an SMS. The SMS app chooses what to put in it: it may shorten a long
 * message, and on Android 15 and later the system hides text it takes for a one-time code
 * from apps like this one. A message cut short can still parse, and parse wrong: a cut inside
 * "Ksh1,500.00" reads as 1, a cut inside a name or a trailing transaction id gives a
 * different person or a different code, and the second copy of the message, whole, would
 * then be stored beside it. So a text that does not look whole is counted as unread and never
 * stored ([looksWhole]). `TrackerIntakeTest` cuts every real sample in MobileMoneyParserTest at
 * every length and holds that no cut is ever stored with a different code, amount or person.
 *
 * Plain Kotlin so the rule has tests; the listener calls it for each message.
 */
object TrackerIntake {

    /** The parser version recorded with each transaction and each unread count. */
    const val PARSER_VERSION = MobileMoneyParser.BUILTIN_VERSION

    /**
     * @param agreedAt when the person agreed to reading, on this phone's clock; null while
     *   reading is off.
     */
    fun read(message: NotificationMessage, agreedAt: Long?): TrackerReading {
        if (agreedAt == null || message.receivedAt <= agreedAt) return TrackerReading.Ignored
        // The listener has already matched the sender; checked again so this rule stands alone.
        if (MobileMoneyParser.networkForSender(message.sender) != message.network.id) return TrackerReading.Ignored

        val text = message.text.trim()
        val unread = TrackerReading.Unread(message.network, MpesaIntake.dayOf(message.receivedAt))
        if (looksHidden(text) || !looksWhole(message.network, text)) return unread

        val tx = MobileMoneyParser.parse(text, message.sender, message.receivedAt) ?: return TrackerReading.NotMoney
        if (tx.type == MobileMoneyParser.TYPE_FAILED || tx.type == MobileMoneyParser.TYPE_IGNORED) {
            return TrackerReading.NotMoney
        }
        if (tx.parseStatus == MobileMoneyParser.STATUS_UNKNOWN) return unread
        val code = tx.ref?.uppercase() ?: return unread
        val type = MpesaType.fromId(tx.type) ?: return unread
        val direction = MpesaDirection.fromId(tx.direction) ?: return unread
        val amount = tx.amount?.takeIf { it >= 0 } ?: return unread

        // A rule that reads the person up to the end of the text, or an id printed last, may
        // have been handed a cut: the rest of the name or id was never in the notification.
        // A name the parser cleaned to nothing ("from 123456 -") is the same cut, one step on.
        if (endsWith(text, tx.counterparty) || endsWith(text, tx.ref)) return unread
        if (tx.counterparty?.isBlank() == true) return unread

        val somebody = tx.counterpartyKind != MobileMoneyParser.KIND_SELF &&
            tx.counterpartyKind != MobileMoneyParser.KIND_UNKNOWN
        return TrackerReading.Read(
            TrackerRecord(
                message.network,
                MpesaRecord(
                    code = code,
                    direction = direction,
                    type = type,
                    amountKes = amount,
                    feeKes = tx.cost,
                    balanceAfterKes = tx.balanceAfter,
                    occurredAt = message.receivedAt,
                    counterpartyName = tx.counterparty.takeIf { somebody },
                    counterpartyPhone = tx.counterpartyPhone.takeIf { somebody },
                ),
            ),
        )
    }

    /**
     * Whether [text] reads as a whole message rather than one the SMS app or the system cut.
     *
     * - No ellipsis at the end: what an app that shortens a message puts there.
     * - Not ending inside a number ("Ksh1,5", "Ksh1,500.0"), which would parse as a different
     *   amount, fee or balance. No M-Pesa confirmation ends in a digit or a comma. Airtel
     *   Money's do end in a digit, always as the balance with one decimal place ("Bal:Ksh
     *   170.0"), so that one ending is whole for Airtel.
     * - Not ending on a shilling figure and a bare point ("balance is Ksh34."), which is
     *   "Ksh34.56" cut after the point. A whole message's last figure keeps its decimals
     *   ("Ksh0.00.").
     * - For Airtel Money, ending the way every real 2026 message does: a full stop, the
     *   balance, or the M-Pesa receipt of a payment to an M-Pesa till ("MPESA ID:UIBS06VLWN").
     *   Its rules read the person up to the end of the text, so a cut ending in a word
     *   ("You have withdrawn Ksh 100 f") would otherwise read as a payment with nobody.
     */
    fun looksWhole(network: MobileMoneyNetwork, text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false
        if (trimmed.endsWith('…') || trimmed.endsWith("...")) return false
        if (CUT_AFTER_POINT.containsMatchIn(trimmed)) return false
        val last = trimmed.last()
        if (last == ',') return false
        return when (network) {
            MobileMoneyNetwork.MPESA -> !last.isDigit()
            MobileMoneyNetwork.AIRTEL -> last == '.' ||
                AIRTEL_BALANCE_ENDING.containsMatchIn(trimmed) ||
                AIRTEL_RECEIPT_ENDING.containsMatchIn(trimmed)
        }
    }

    /**
     * A text with no digit at all cannot be a mobile money confirmation, which always prints an
     * amount. Android 15 and later replace a notification's text for apps like this one when
     * the system takes it for a one-time code, and the replacement is not documented, so any
     * such text from a mobile money sender is counted as unread rather than passed over.
     */
    fun looksHidden(text: String): Boolean = text.none { it.isDigit() }

    /**
     * Whether the text stops exactly where [part] does. A whole message closes a trailing id
     * or name with a full stop ("TID V3RAY6227A9."), so one that stops on it bare may be cut.
     */
    private fun endsWith(text: String, part: String?): Boolean {
        val tail = part?.let(::squash)?.takeIf { it.isNotEmpty() } ?: return false
        return squash(text).endsWith(tail)
    }

    /** Upper case, one space between words: the parser cleans names and ids this way, roughly. */
    private fun squash(value: String): String = value.uppercase().replace(SPACES, " ").trim()

    private val SPACES = Regex("""\s+""")

    /** A shilling figure with no decimals and a point at the very end: "Ksh34.", "KES 1,000." */
    private val CUT_AFTER_POINT = Regex("""(?i)(?:KES|KSHS?)\.?\s*[\d,]*\d\.$""")

    /** "Bal:Ksh 170.0", "Bal Ksh 49.0", "balance is KES 1,000.00" at the very end. */
    private val AIRTEL_BALANCE_ENDING =
        Regex("""(?i)\bbal(?:ance)?\s*:?\s*(?:is\s+)?(?:KES|KSHS?)\.?\s*[\d,]*\d\.\d+$""")

    /** "MPESA ID:UIBS06VLWN" at the very end: the M-Pesa code is ten characters. */
    private val AIRTEL_RECEIPT_ENDING = Regex("""(?i)\bM-?PESA\s+ID\s*:\s*[A-Z0-9]{10}$""")
}
