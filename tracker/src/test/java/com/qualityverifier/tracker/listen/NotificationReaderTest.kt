package com.qualityverifier.tracker.listen

import com.qualityverifier.domain.MobileMoneyNetwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A built notification, as the reader sees one, that records every field it was asked for. A
 * test can then prove that a dropped notification's text was never read, which is the rule:
 * another app's notification, or another sender's, is dropped before its text is touched.
 */
class BuiltNotification(
    override val packageName: String = SMS_APP,
    override val isGroupSummary: Boolean = false,
    override val time: Long = 1_762_934_400_000,
    private val title: String? = null,
    private val conversationTitle: String? = null,
    private val messages: List<Pair<String?, String>> = emptyList(),
    private val messageTimes: List<Long> = emptyList(),
    private val senderUris: List<String?> = emptyList(),
    private val bigText: String? = null,
    private val text: String? = null,
    private val lines: List<String> = emptyList(),
) : NotificationSource {
    val read = mutableListOf<String>()

    /** Whether any message text, big text, text or line was read. */
    val anyTextRead: Boolean get() = read.any { it.startsWith("messageText") || it in setOf("bigText", "text", "textLines") }

    override fun title(): String? = title.also { read += "title" }
    override fun conversationTitle(): String? = conversationTitle.also { read += "conversationTitle" }
    override fun messageHeaders(): List<MessageHeader> {
        read += "messageHeaders"
        return messages.mapIndexed { index, (sender, _) ->
            MessageHeader(sender, messageTimes.getOrElse(index) { time + index }, senderUris.getOrNull(index))
        }
    }
    override fun messageText(index: Int): String? = messages.getOrNull(index)?.second.also { read += "messageText$index" }
    override fun bigText(): String? = bigText.also { read += "bigText" }
    override fun text(): String? = text.also { read += "text" }
    override fun textLines(): List<String> = lines.also { read += "textLines" }

    companion object {
        const val SMS_APP = "com.google.android.apps.messaging"
    }
}

class NotificationReaderTest {

    private val send = "SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. New M-PESA balance is Ksh34.56. Transaction cost, Ksh0.00."
    private val receive = "UIGRL6LYLB Confirmed. You have received Ksh450.00 from IM BANK LIMITED- APP on 16/9/26 at 8:29 PM. New M-PESA balance is Ksh450.00. Buy goods with M-PESA."

    private fun read(notification: BuiltNotification) = NotificationReader.read(notification, BuiltNotification.SMS_APP)

    @Test
    fun `another app's notification is dropped with nothing but its package read`() {
        val other = BuiltNotification(packageName = "com.whatsapp", title = "MPESA", text = send, messages = listOf("MPESA" to send))

        assertEquals(ReaderOutcome.Dropped(DropReason.NOT_THE_SMS_APP), read(other))
        assertTrue("read ${other.read}", other.read.isEmpty())
    }

    @Test
    fun `with no default SMS app nothing is read`() {
        val sms = BuiltNotification(title = "MPESA", text = send)

        assertEquals(ReaderOutcome.Dropped(DropReason.NOT_THE_SMS_APP), NotificationReader.read(sms, defaultSmsPackage = null))
        assertTrue(sms.read.isEmpty())
    }

    @Test
    fun `an SMS from any other sender is dropped before its text is read`() {
        val friend = BuiltNotification(title = "Wanjiku", text = "Tuonane kesho saa nne", bigText = "Tuonane kesho saa nne")

        assertEquals(ReaderOutcome.Dropped(DropReason.NOT_A_MONEY_SENDER), read(friend))
        assertFalse("read ${friend.read}", friend.anyTextRead)
    }

    @Test
    fun `a group summary is dropped before its text is read`() {
        val summary = BuiltNotification(isGroupSummary = true, title = "MPESA", text = "2 new messages", lines = listOf(send, receive))

        assertEquals(ReaderOutcome.Dropped(DropReason.GROUP_SUMMARY), read(summary))
        assertFalse(summary.anyTextRead)
    }

    @Test
    fun `a messaging-style notification with two messages gives two messages`() {
        val conversation = BuiltNotification(title = "MPESA", messages = listOf("MPESA" to send, "MPESA" to receive), messageTimes = listOf(10L, 20L))

        val messages = (read(conversation) as ReaderOutcome.Messages).messages

        assertEquals(listOf(send, receive), messages.map { it.text })
        assertEquals(listOf(10L, 20L), messages.map { it.receivedAt })
        assertTrue(messages.all { it.network == MobileMoneyNetwork.MPESA })
    }

    @Test
    fun `in one conversation another sender's message is never read`() {
        // A group conversation, or the person's own reply: only the money sender's text is read.
        val mixed = BuiltNotification(messages = listOf("Wanjiku" to "Tuonane kesho", "MPESA" to send))

        val messages = (read(mixed) as ReaderOutcome.Messages).messages

        assertEquals(listOf(send), messages.map { it.text })
        assertFalse("read ${mixed.read}", "messageText0" in mixed.read)
    }

    @Test
    fun `a message with no sender is the person's own, and is never read`() {
        val own = BuiltNotification(conversationTitle = "MPESA", title = "MPESA", messages = listOf(null to send))

        assertEquals(ReaderOutcome.Dropped(DropReason.NOT_A_MONEY_SENDER), read(own))
        assertFalse(own.anyTextRead)
    }

    @Test
    fun `a contact saved under a mobile money name, with a phone number, is never read`() {
        val contact = BuiltNotification(messages = listOf("MPESA" to send), senderUris = listOf("tel:+254712345678"))
        val shortcode = BuiltNotification(messages = listOf("MPESA" to send), senderUris = listOf("tel:MPESA"))

        assertEquals(ReaderOutcome.Dropped(DropReason.NOT_A_MONEY_SENDER), read(contact))
        assertFalse(contact.anyTextRead)
        assertTrue(read(shortcode) is ReaderOutcome.Messages)
    }

    @Test
    fun `an Airtel Money message is read as Airtel Money`() {
        val conversation = BuiltNotification(messages = listOf("AirtelMoney" to "U3RAGOD5ZEU. Ksh 200 received from JOHN DOE 254733000222 on 15/09/26 10:28 AM. Bal:Ksh 390.0"))

        assertEquals(MobileMoneyNetwork.AIRTEL, (read(conversation) as ReaderOutcome.Messages).messages.single().network)
    }

    @Test
    fun `inbox lines and untimed messages are marked as undated`() {
        val inbox = BuiltNotification(title = "MPESA", lines = listOf(send, receive))
        val untimed = BuiltNotification(messages = listOf("MPESA" to send), messageTimes = listOf(0L))
        val single = BuiltNotification(title = "MPESA", text = send)

        assertTrue((read(inbox) as ReaderOutcome.Messages).messages.none { it.exactTime })
        assertFalse((read(untimed) as ReaderOutcome.Messages).messages.single().exactTime)
        assertTrue((read(single) as ReaderOutcome.Messages).messages.single().exactTime)
    }

    @Test
    fun `the big text is taken over a shortened plain text`() {
        val big = BuiltNotification(title = "MPESA", text = send.take(40) + "…", bigText = send)

        assertEquals(send, (read(big) as ReaderOutcome.Messages).messages.single().text)
    }

    @Test
    fun `an inbox-style notification is one message per line`() {
        val inbox = BuiltNotification(title = "M-PESA", text = "2 new messages", lines = listOf(send, receive))

        assertEquals(listOf(send, receive), (read(inbox) as ReaderOutcome.Messages).messages.map { it.text })
    }

    @Test
    fun `a saved contact name for the sender still reads as the sender`() {
        // "M-Pesa" and "Safaricom M-PESA" normalise to sender ids the parser knows.
        listOf("M-Pesa", "MPESA", "Safaricom M-PESA").forEach { title ->
            val sms = BuiltNotification(title = title, text = send)
            assertTrue(title, read(sms) is ReaderOutcome.Messages)
        }
    }
}
