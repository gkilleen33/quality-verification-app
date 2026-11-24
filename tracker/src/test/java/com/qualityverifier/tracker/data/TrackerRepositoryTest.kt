package com.qualityverifier.tracker.data

import android.content.SharedPreferences
import com.qualityverifier.mpesa.NotificationMessage
import com.qualityverifier.tracker.listen.BuiltNotification
import com.qualityverifier.tracker.listen.NotificationReader
import com.qualityverifier.tracker.listen.ReaderOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SharedPreferences in a map, enough for TrackerPreference. */
class MapPreferences : SharedPreferences {
    val values = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = values
    override fun getString(key: String, defValue: String?) = values[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: Set<String>?) = values[key] as Set<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removed = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?) = apply { changes[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { changes[key] = values?.toSet() }
        override fun putInt(key: String, value: Int) = apply { changes[key] = value }
        override fun putLong(key: String, value: Long) = apply { changes[key] = value }
        override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
        override fun remove(key: String) = apply { removed += key }
        override fun clear() = apply { clear = true }
        override fun commit(): Boolean {
            apply()
            return true
        }
        override fun apply() {
            if (clear) values.clear()
            removed.forEach { values.remove(it) }
            values.putAll(changes)
        }
    }
}

/** PesaLog's tables in memory, with Room's conflict rules. */
class FakeDao : TrackerDao {
    val rows = MutableStateFlow<Map<Pair<String, String>, TransactionEntity>>(emptyMap())
    val unread = mutableMapOf<Pair<String, String>, UnreadDayEntity>()
    private val counted = mutableMapOf<String, CountedUnreadEntity>()

    override suspend fun insertIfNew(transaction: TransactionEntity): Long {
        val key = transaction.network to transaction.code
        if (key in rows.value) return -1L
        rows.value = rows.value + (key to transaction)
        return rows.value.size.toLong()
    }

    override suspend fun fillMissing(network: String, code: String, feeKes: Double?, balanceAfterKes: Double?) {
        val row = rows.value[network to code]?.takeIf { it.uploadedAt == null } ?: return
        rows.value = rows.value + ((network to code) to row.copy(
            feeKes = row.feeKes ?: feeKes,
            balanceAfterKes = row.balanceAfterKes ?: balanceAfterKes,
        ))
    }

    override fun observeAll(): Flow<List<TransactionEntity>> = rows.map { it.values.sortedByDescending { r -> r.occurredAt } }
    override fun observe(network: String, code: String): Flow<TransactionEntity?> = rows.map { it[network to code] }

    override suspend fun annotate(network: String, code: String, category: String?, comment: String?, at: Long) {
        val row = rows.value[network to code] ?: return
        rows.value = rows.value + ((network to code) to row.copy(category = category, comment = comment, annotatedAt = at))
    }

    override suspend fun notUploaded(limit: Int) = rows.value.values.filter { it.uploadedAt == null }.sortedBy { it.occurredAt }.take(limit)

    override suspend fun markUploaded(network: String, code: String, at: Long) {
        val row = rows.value[network to code] ?: return
        rows.value = rows.value + ((network to code) to row.copy(uploadedAt = at))
    }

    override suspend fun unsyncedAnnotations() = rows.value.values.filter {
        it.uploadedAt != null && it.annotatedAt != null && (it.annotationSyncedAt == null || it.annotationSyncedAt < it.annotatedAt)
    }

    override suspend fun markAnnotationSynced(network: String, code: String, annotatedAt: Long) {
        val row = rows.value[network to code]?.takeIf { it.annotatedAt == annotatedAt } ?: return
        rows.value = rows.value + ((network to code) to row.copy(annotationSyncedAt = annotatedAt))
    }

    override suspend fun unreadDay(network: String, day: String) = unread[network to day]
    override suspend fun putUnreadDay(day: UnreadDayEntity) {
        unread[day.network to day.day] = day
    }

    override suspend fun insertCounted(counted: CountedUnreadEntity): Long {
        if (counted.key in this.counted) return -1L
        this.counted[counted.key] = counted
        return 1L
    }

    override suspend fun pruneCounted(before: Long) {
        counted.values.removeAll { it.countedAt < before }
    }

    override suspend fun unsyncedUnreadDays() = unread.values.filter { it.syncedAt == null || it.syncedAt < it.updatedAt }

    override suspend fun markUnreadDaySynced(network: String, day: String, updatedAt: Long) {
        val row = unread[network to day]?.takeIf { it.updatedAt == updatedAt } ?: return
        unread[network to day] = row.copy(syncedAt = updatedAt)
    }
}

/** The server as the repository sees it: records what was sent, answers what each test sets. */
class FakeApi : TrackerApi {
    val uploaded = mutableListOf<TransactionEntity>()
    val agreed = mutableListOf<String>()
    val withdrawn = mutableListOf<String>()
    var refuseSending = false
    var online = true
    var deletions = 0

    override suspend fun upload(rows: List<TransactionEntity>): UploadOutcome = when {
        !online -> UploadOutcome.Later
        refuseSending -> UploadOutcome.SendingRefused
        else -> {
            uploaded += rows
            UploadOutcome.Done(rows.map { TransactionKey(it.network, it.code) }, emptyList())
        }
    }

    override suspend fun annotate(row: TransactionEntity) = NoteOutcome.SAVED
    override suspend fun unread(days: List<UnreadDayEntity>, parserVersion: Int): Boolean? = if (refuseSending) null else online

    override suspend fun agree(kind: String): ConsentOutcome {
        if (!online) return ConsentOutcome.Offline
        agreed += kind
        return ConsentOutcome.Agreed
    }

    override suspend fun withdraw(kind: String): Boolean {
        if (!online) return false
        withdrawn += kind
        return true
    }

    override suspend fun answers() = QuestionAnswers(readAgreed = "tracker_read" in agreed, sendAgreed = "tracker_send" in agreed)

    override suspend fun deleteRecords(): DeleteOutcome {
        if (!online) return DeleteOutcome.Offline
        deletions++
        return DeleteOutcome.Deleted(uploaded.size, 0, sendingWithdrawn = true)
    }

    override suspend fun accountCheck() = AccountCheck.USABLE
}

class TrackerRepositoryTest {

    private var clock = 1_762_900_000_000L
    private val dao = FakeDao()
    private val api = FakeApi()
    private val prefs = TrackerPreference(MapPreferences())
    private val repository = TrackerRepository(dao, prefs, api, now = { clock })

    private val send = "SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. New M-PESA balance is Ksh34.56. Transaction cost, Ksh0.00."
    private val receive = "UIGRL6LYLB Confirmed. You have received Ksh450.00 from IM BANK LIMITED- APP on 16/9/26 at 8:29 PM. New M-PESA balance is Ksh450.00. Buy goods with M-PESA."
    private val third = "UIHRL6MR9Q Confirmed. Ksh40.00 sent to JANE DOE on 17/9/26 at 7:50 AM. New M-PESA balance is Ksh0.00. Transaction cost, Ksh0.00. Amount you can transact within the day is 499,910.00."

    private fun messagesOf(notification: BuiltNotification): List<NotificationMessage> =
        (NotificationReader.read(notification, BuiltNotification.SMS_APP) as ReaderOutcome.Messages).messages

    private suspend fun agreeToBoth() {
        repository.agreeToReading(clock)
        repository.agreeToSending()
        clock += 1_000
    }

    @Test
    fun `nothing is stored while reading is off`() = runTest {
        val messages = messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 10))

        messages.forEach { assertEquals(TakeOutcome.NOTHING, repository.take(it)) }
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `a message from before the agreement is not stored`() = runTest {
        agreeToBoth()
        val old = messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock - 60_000))

        assertEquals(TakeOutcome.NOTHING, repository.take(old.single()))
        assertTrue(dao.rows.value.isEmpty())
    }

    @Test
    fun `a messaging-style notification with two messages stores two transactions`() = runTest {
        agreeToBoth()
        val conversation = BuiltNotification(messages = listOf("MPESA" to send, "MPESA" to receive), messageTimes = listOf(clock + 1, clock + 2))

        messagesOf(conversation).forEach { assertEquals(TakeOutcome.STORED, repository.take(it)) }

        assertEquals(setOf("mpesa" to "SFA1B2C3D5", "mpesa" to "UIGRL6LYLB"), dao.rows.value.keys)
    }

    @Test
    fun `an updated notification and a repost store each payment once`() = runTest {
        agreeToBoth()
        val first = BuiltNotification(messages = listOf("MPESA" to send), messageTimes = listOf(clock + 1))
        // Updated in place: Android posts it again with every message, old and new.
        val updated = BuiltNotification(
            messages = listOf("MPESA" to send, "MPESA" to receive, "MPESA" to third),
            messageTimes = listOf(clock + 1, clock + 2, clock + 3),
        )

        messagesOf(first).forEach { repository.take(it) }
        val outcomes = messagesOf(updated).map { repository.take(it) }
        // And the same notification read again when the listener reconnects.
        messagesOf(updated).forEach { assertEquals(TakeOutcome.ALREADY_HELD, repository.take(it)) }

        assertEquals(listOf(TakeOutcome.ALREADY_HELD, TakeOutcome.STORED, TakeOutcome.STORED), outcomes)
        assertEquals(3, dao.rows.value.size)
    }

    @Test
    fun `a shortened message counts as unread once for its day, however often it is seen`() = runTest {
        agreeToBoth()
        val shortened = BuiltNotification(title = "MPESA", text = send.take(60) + "…", time = clock + 5)

        assertEquals(TakeOutcome.COUNTED, repository.take(messagesOf(shortened).single()))
        assertEquals(TakeOutcome.ALREADY_HELD, repository.take(messagesOf(shortened).single()))

        assertTrue(dao.rows.value.isEmpty())
        assertEquals(1, dao.unread.values.single().unread)
        assertEquals("mpesa", dao.unread.values.single().network)
    }

    @Test
    fun `turning reading off stops storing at once, and tells the server when it can`() = runTest {
        agreeToBoth()
        api.online = false
        repository.stopReading()

        val message = messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 10)).single()
        assertEquals(TakeOutcome.NOTHING, repository.take(message))
        assertFalse(prefs.sending)
        assertTrue(TrackerPreference.READ in prefs.pendingWithdrawals)

        api.online = true
        repository.push()
        assertEquals(listOf(TrackerPreference.READ), api.withdrawn)
        assertTrue(prefs.pendingWithdrawals.isEmpty())
    }

    @Test
    fun `a push uploads each payment once and nothing while sending is off`() = runTest {
        repository.agreeToReading(clock)
        clock += 1_000
        repository.take(messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 1)).single())

        repository.push()
        assertTrue("sent with sending off: ${api.uploaded}", api.uploaded.isEmpty())

        repository.agreeToSending()
        repository.push()
        repository.push()
        assertEquals(1, api.uploaded.size)
    }

    @Test
    fun `row 32 - a receipt keeps the masked number M-Pesa printed and uploads it with the name`() = runTest {
        agreeToBoth()
        val receipt = "UINRL7DIVX Confirmed.You have received Ksh200.00 from JANE DOE 0720***123 on 23/9/26 at 9:30 AM New M-PESA balance is Ksh210.00."
        repository.take(messagesOf(BuiltNotification(title = "MPESA", text = receipt, time = clock + 1)).single())

        repository.push()

        val sent = api.uploaded.single()
        assertEquals("JANE DOE", sent.counterpartyName)
        assertEquals("0720***123", sent.counterpartyMasked)
        assertEquals(null, sent.counterpartyPhone)
    }

    @Test
    fun `the server refusing sending turns sending off here`() = runTest {
        agreeToBoth()
        repository.take(messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 1)).single())
        api.refuseSending = true

        repository.push()

        assertFalse(prefs.sending)
        assertTrue(prefs.pendingWithdrawals.isEmpty())
    }

    @Test
    fun `deleting the server's copy stops sending, and the deleted payments are never sent again`() = runTest {
        agreeToBoth()
        repository.take(messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 1)).single())
        repository.push()

        val outcome = repository.deleteFromServer()

        assertTrue(outcome is DeleteOutcome.Deleted)
        assertFalse(prefs.sending)
        // Nothing to withdraw: the server withdrew sending itself.
        assertTrue(prefs.pendingWithdrawals.isEmpty())
        repository.agreeToSending()
        repository.push()
        assertEquals(1, api.uploaded.size)
    }

    @Test
    fun `a copy cut before the balance is filled in by the whole copy, before upload`() = runTest {
        repository.agreeToReading(clock)
        clock += 1_000
        val cut = "SFA1B2C3D5 Confirmed. Ksh200.00 sent to JANE MERCHANT on 9/9/26 at 5:00 PM. New M-PESA"
        assertEquals(TakeOutcome.STORED, repository.take(messagesOf(BuiltNotification(title = "MPESA", text = cut, time = clock + 1)).single()))
        assertEquals(null, dao.rows.value.values.single().balanceAfterKes)

        repository.take(messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 1)).single())

        val row = dao.rows.value.values.single()
        assertEquals(34.56, row.balanceAfterKes!!, 0.0)
        assertEquals(200.0, row.amountKes, 0.0)
    }

    @Test
    fun `an inbox line from before the agreement is not stored, though the notification is newer`() = runTest {
        agreeToBoth()
        // The 1/1/25 payment is still on screen when a newer message updates the notification.
        val old = "SFA1B2C3D4 Confirmed. Ksh500.00 sent to JOHN DOE 0722123456 on 1/1/25 at 10:15 AM. New M-PESA balance is Ksh1,234.56."
        val inbox = BuiltNotification(title = "MPESA", lines = listOf(old, send), time = clock + 10)

        val outcomes = messagesOf(inbox).map { repository.take(it) }

        assertEquals(listOf(TakeOutcome.NOTHING, TakeOutcome.STORED), outcomes)
        assertEquals(setOf("mpesa" to "SFA1B2C3D5"), dao.rows.value.keys)
    }

    @Test
    fun `a comment is cut at the server's bound`() = runTest {
        agreeToBoth()
        repository.take(messagesOf(BuiltNotification(title = "MPESA", text = send, time = clock + 1)).single())

        repository.annotate("mpesa", "SFA1B2C3D5", null, "x".repeat(600))

        assertEquals(500, dao.rows.value.values.single().comment!!.length)
    }
}
