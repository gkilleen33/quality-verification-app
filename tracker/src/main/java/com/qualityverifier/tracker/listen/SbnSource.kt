package com.qualityverifier.tracker.listen

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification

/**
 * A [StatusBarNotification] as [NotificationReader] reads it. Each field is read from the
 * notification's extras only when the reader asks for it, so a notification the reader drops
 * by its package or its sender never has its text taken out of the bundle.
 *
 * The keys are Android's own (Notification.EXTRA_* and MessagingStyle.Message's bundle keys,
 * "text", "time", "sender", "sender_person"), read from the bundle rather than through
 * MessagingStyle, which an app other than the poster cannot rebuild on older Android versions.
 */
class SbnSource(private val sbn: StatusBarNotification) : NotificationSource {

    private val notification: Notification get() = sbn.notification
    private val extras: Bundle get() = notification.extras

    override val packageName: String get() = sbn.packageName

    override val isGroupSummary: Boolean
        get() = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

    override val time: Long
        get() = notification.`when`.takeIf { it > 0 } ?: sbn.postTime

    override fun title(): String? = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()

    override fun conversationTitle(): String? =
        extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()

    override fun messageHeaders(): List<MessageHeader> = messages().map { bundle ->
        MessageHeader(sender = senderOf(bundle), time = bundle.getLong(KEY_TIME), senderUri = senderUriOf(bundle))
    }

    override fun messageText(index: Int): String? = messages().getOrNull(index)?.getCharSequence(KEY_TEXT)?.toString()

    override fun bigText(): String? = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()

    override fun text(): String? = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()

    override fun textLines(): List<String> =
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.mapNotNull { it?.toString() }.orEmpty()

    @Suppress("DEPRECATION") // The typed overload is API 33; this one reads the same array.
    private fun messages(): List<Bundle> =
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.filterIsInstance<Bundle>().orEmpty()

    /** "sender" before Android 9; "sender_person" from 9, whose name is the sender. */
    @Suppress("DEPRECATION")
    private fun senderOf(bundle: Bundle): String? {
        bundle.getCharSequence(KEY_SENDER)?.let { return it.toString() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return bundle.getParcelable<Person>(KEY_SENDER_PERSON)?.name?.toString()
        }
        return null
    }

    /** The sender Person's URI, from Android 9; null before, or when the SMS app sets none. */
    @Suppress("DEPRECATION")
    private fun senderUriOf(bundle: Bundle): String? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) bundle.getParcelable<Person>(KEY_SENDER_PERSON)?.uri else null

    private companion object {
        const val KEY_TEXT = "text"
        const val KEY_TIME = "time"
        const val KEY_SENDER = "sender"
        const val KEY_SENDER_PERSON = "sender_person"
    }
}
