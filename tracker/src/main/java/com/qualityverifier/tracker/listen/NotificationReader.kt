package com.qualityverifier.tracker.listen

import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.mpesa.MobileMoneyParser
import com.qualityverifier.mpesa.NotificationMessage

/** One message of a messaging-style notification, without its text: who sent it, and when. */
data class MessageHeader(val sender: String?, val time: Long)

/**
 * A posted notification as [NotificationReader] reads it, one field per call.
 *
 * Separate from Android's StatusBarNotification so the reader's rules run on the plain JVM
 * with a built notification (`NotificationReaderTest`), and so a test can record which fields
 * were read: a notification from another app, or from a sender that is not a mobile money
 * sender id, must be dropped before any of its text is touched. [SbnSource] is the Android
 * side, and reads each field only when it is asked for.
 */
interface NotificationSource {
    val packageName: String

    /** True for a group summary (Notification.FLAG_GROUP_SUMMARY). */
    val isGroupSummary: Boolean

    /** When the notification says its content happened (Notification.when), or when it was posted. */
    val time: Long

    /** EXTRA_TITLE: for an SMS, the sender id or the contact's name, "MPESA". */
    fun title(): String?

    /** EXTRA_CONVERSATION_TITLE of a messaging-style notification. */
    fun conversationTitle(): String?

    /** EXTRA_MESSAGES' senders and times, in order. Empty for any other style. */
    fun messageHeaders(): List<MessageHeader>

    /** The text of message [index] in [messageHeaders]. */
    fun messageText(index: Int): String?

    /** EXTRA_BIG_TEXT: the whole message, in the expanded form of a big-text notification. */
    fun bigText(): String?

    /** EXTRA_TEXT: the main text. */
    fun text(): String?

    /** EXTRA_TEXT_LINES: one line per message in an inbox-style notification. */
    fun textLines(): List<String>
}

/** Why a notification gave PesaLog nothing, in the order the reader checks. */
enum class DropReason {
    /** Not posted by the phone's default SMS app. Nothing but the package name was read. */
    NOT_THE_SMS_APP,

    /** A summary of a group whose own notifications carry the messages. */
    GROUP_SUMMARY,

    /** From the SMS app, but no message in it is from a mobile money sender id. No text was read. */
    NOT_A_MONEY_SENDER,
}

sealed interface ReaderOutcome {
    data class Messages(val messages: List<NotificationMessage>) : ReaderOutcome
    data class Dropped(val reason: DropReason) : ReaderOutcome
}

/**
 * Which notifications PesaLog reads, and the message text it takes from each (build prompt,
 * 13 November 2026).
 *
 * Android gives a notification listener every notification on the phone. PesaLog reads one
 * only when it comes from the phone's default SMS app and its sender is a mobile money
 * sender id; anything else is dropped before its text is read, and nothing about it is
 * stored or counted. Each drop is marked below, in the order it happens.
 *
 * The text is the whole message where the notification carries it: a messaging-style
 * notification's own messages (Google Messages, and most SMS apps since Android 7), then the
 * big text, then the plain text, taking the longer of the last two. An inbox-style
 * notification with several lines is several messages from one sender. Whether the text
 * found is whole, and whether it is money, is TrackerIntake's to decide.
 */
object NotificationReader {

    fun read(
        source: NotificationSource,
        defaultSmsPackage: String?,
        networkFor: (sender: String?) -> MobileMoneyNetwork? = ::networkOf,
    ): ReaderOutcome {
        // Drop 1: another app's notification, or no default SMS app. Only the package name
        // has been read.
        if (defaultSmsPackage == null || source.packageName != defaultSmsPackage) {
            return ReaderOutcome.Dropped(DropReason.NOT_THE_SMS_APP)
        }
        // Drop 2: a group summary. Its children are posted as notifications of their own and
        // carry each message whole; the summary repeats them, often shortened.
        if (source.isGroupSummary) return ReaderOutcome.Dropped(DropReason.GROUP_SUMMARY)

        val headers = source.messageHeaders()
        if (headers.isNotEmpty()) {
            val conversation = source.conversationTitle() ?: source.title()
            val messages = headers.mapIndexedNotNull { index, header ->
                val sender = header.sender ?: conversation
                // Drop 3, per message: a message from any other sender. Its text is never read.
                val network = networkFor(sender) ?: return@mapIndexedNotNull null
                val text = source.messageText(index)?.takeIf { it.isNotBlank() } ?: return@mapIndexedNotNull null
                NotificationMessage(network, sender!!, text, header.time.takeIf { it > 0 } ?: source.time)
            }
            return if (messages.isEmpty()) {
                ReaderOutcome.Dropped(DropReason.NOT_A_MONEY_SENDER)
            } else {
                ReaderOutcome.Messages(messages)
            }
        }

        // Drop 3: a notification from any other sender. Only its title has been read.
        val sender = source.title()
        val network = networkFor(sender) ?: return ReaderOutcome.Dropped(DropReason.NOT_A_MONEY_SENDER)
        val lines = source.textLines().filter { it.isNotBlank() }
        val texts = if (lines.size > 1) {
            lines
        } else {
            listOfNotNull(listOfNotNull(source.bigText(), source.text()).filter { it.isNotBlank() }.maxByOrNull { it.length })
        }
        if (texts.isEmpty()) return ReaderOutcome.Dropped(DropReason.NOT_A_MONEY_SENDER)
        return ReaderOutcome.Messages(texts.map { NotificationMessage(network, sender!!, it, source.time) })
    }

    /** The network of a mobile money sender id, by MobileMoneyParser's own list; null for anybody else. */
    fun networkOf(sender: String?): MobileMoneyNetwork? =
        MobileMoneyNetwork.fromId(MobileMoneyParser.networkForSender(sender))
}
