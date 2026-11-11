package com.qualityverifier.mpesa

import android.content.SharedPreferences
import com.qualityverifier.data.db.MpesaDao
import com.qualityverifier.data.db.MpesaTransactionEntity
import com.qualityverifier.data.db.MpesaUnreadDayEntity
import com.qualityverifier.data.mpesa.ConsentOutcome
import com.qualityverifier.data.mpesa.MpesaApi
import com.qualityverifier.data.mpesa.MpesaPreference
import com.qualityverifier.data.mpesa.MpesaRepository
import com.qualityverifier.data.mpesa.NoteOutcome
import com.qualityverifier.data.mpesa.UploadOutcome
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What the phone keeps, what it sends, and when it stops. */
class MpesaRepositoryTest {

    private val dao = FakeMpesaDao()
    private val api = FakeMpesaApi()
    private val prefs = MpesaPreference(FakePrefs())
    private var clock = 1_000L
    private val repo = MpesaRepository(dao, prefs, api, now = { clock })

    private fun record(code: String, direction: MpesaDirection = MpesaDirection.OUT) = MpesaRecord(
        code = code, direction = direction, type = MpesaType.SEND, amountKes = 500.0, feeKes = 7.0,
        balanceAfterKes = 1000.0, occurredAt = 900L, counterpartyName = "JOHN DOE", counterpartyPhone = "254722123456",
    )

    private suspend fun readingAndSending() {
        assertEquals(ConsentOutcome.Agreed, repo.agreeToReading(at = 500L))
        assertEquals(ConsentOutcome.Agreed, repo.agreeToSending())
        api.agreed.clear()
    }

    @Test
    fun `a transaction read twice is stored once`() = runTest {
        repo.agreeToReading(at = 500L)
        assertTrue(repo.take(MpesaReading.Read(record("SKA1B2C3D4"))))
        assertFalse(repo.take(MpesaReading.Read(record("SKA1B2C3D4"))))
        assertEquals(1, dao.rows.size)
    }

    @Test
    fun `an unread message is counted by day, and nothing else is stored`() = runTest {
        repo.agreeToReading(at = 500L)
        repo.take(MpesaReading.Unread(LocalDate.of(2026, 11, 12)))
        repo.take(MpesaReading.Unread(LocalDate.of(2026, 11, 12)))
        repo.take(MpesaReading.Ignored)
        repo.take(MpesaReading.NotMoney)
        assertEquals(2, dao.days["2026-11-12"]?.unread)
        assertTrue(dao.rows.isEmpty())
    }

    @Test
    fun `reading starts only once the server has the agreement`() = runTest {
        api.consent = ConsentOutcome.Offline
        assertEquals(ConsentOutcome.Offline, repo.agreeToReading(at = 500L))
        assertFalse(repo.switches.value.reading)
        assertNull(repo.scanFrom)

        api.consent = ConsentOutcome.Agreed
        repo.agreeToReading(at = 500L)
        assertEquals(500L, repo.switches.value.readingSince)
        // The scan starts at the agreement, never before it.
        assertEquals(500L, repo.scanFrom)
        assertEquals(listOf("mpesa_read"), api.agreed)
    }

    @Test
    fun `sending cannot be agreed before reading`() = runTest {
        assertEquals(ConsentOutcome.Failed, repo.agreeToSending())
        assertTrue(api.agreed.isEmpty())
        assertFalse(repo.switches.value.sending)
    }

    @Test
    fun `nothing is sent while sending is off`() = runTest {
        repo.agreeToReading(at = 500L)
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))

        assertEquals(0, repo.push())
        assertTrue(api.uploads.isEmpty())
    }

    @Test
    fun `a push sends new transactions once`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        repo.take(MpesaReading.Read(record("SKA1B2C3D5")))

        assertEquals(2, repo.push())
        assertEquals(0, repo.push())
        assertEquals(1, api.uploads.size)
        assertEquals(listOf("SKA1B2C3D4", "SKA1B2C3D5"), api.uploads.single().map { it.code })
    }

    @Test
    fun `an upload that fails is kept and sent next time`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        api.upload = UploadOutcome.Later

        assertEquals(0, repo.push())
        api.upload = null
        assertEquals(1, repo.push())
        assertEquals(2, api.uploads.size)
    }

    @Test
    fun `a server without the agreement turns sending off here too`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        api.upload = UploadOutcome.SendingRefused

        repo.push()

        assertFalse(repo.switches.value.sending)
        assertTrue(repo.switches.value.reading)
        // Nothing for the server to hear: it already holds no agreement.
        assertTrue(api.withdrawn.isEmpty())
        assertNull(dao.rows.values.single().uploadedAt)
    }

    @Test
    fun `a note is sent after its transaction, and again only when it changes`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        repo.annotate("SKA1B2C3D4", MpesaCategory.WAGES, "piece-1", "  Kevin  ")

        repo.push()
        assertEquals(1, api.notes.size)
        assertEquals("Kevin", api.notes.single().comment)
        repo.push()
        assertEquals(1, api.notes.size)

        clock = 2_000L
        repo.annotate("SKA1B2C3D4", MpesaCategory.MATERIAL_PURCHASES, null, null)
        repo.push()
        assertEquals(2, api.notes.size)
        assertEquals("material_purchases", api.notes.last().category)
    }

    @Test
    fun `a note whose piece the server lacks is kept for later`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        repo.annotate("SKA1B2C3D4", MpesaCategory.WAGES, "piece-1", null)
        api.note = NoteOutcome.LATER

        repo.push()
        api.note = NoteOutcome.SAVED
        repo.push()

        assertEquals(2, api.notes.size)
        assertEquals(1_000L, dao.rows.values.single().annotationSyncedAt)
    }

    @Test
    fun `unread counts are sent once per change`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Unread(LocalDate.of(2026, 11, 12)))

        repo.push()
        repo.push()
        clock = 2_000L
        repo.take(MpesaReading.Unread(LocalDate.of(2026, 11, 12)))
        repo.push()

        assertEquals(listOf(1, 2), api.unreadSent.map { it.single().unread })
    }

    @Test
    fun `turning reading off stops reading and sending at once, offline too, and tells the server later`() = runTest {
        readingAndSending()
        api.reachable = false

        repo.stopReading()

        assertFalse(repo.switches.value.reading)
        assertFalse(repo.switches.value.sending)
        assertNull(repo.scanFrom)
        assertTrue(api.withdrawn.isEmpty())

        api.reachable = true
        repo.push()
        assertEquals(listOf("mpesa_read"), api.withdrawn)
        repo.push()
        assertEquals(listOf("mpesa_read"), api.withdrawn)
    }

    @Test
    fun `turning sending off keeps reading and stops uploads`() = runTest {
        readingAndSending()
        repo.stopSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))

        repo.push()

        assertTrue(repo.switches.value.reading)
        assertEquals(listOf("mpesa_send"), api.withdrawn)
        assertTrue(api.uploads.isEmpty())
    }

    @Test
    fun `a withdrawal made offline reaches the server before a new agreement`() = runTest {
        readingAndSending()
        api.reachable = false
        repo.stopReading()
        api.reachable = true

        repo.agreeToReading(at = 3_000L)

        assertEquals(listOf("withdraw mpesa_read", "agree mpesa_read"), api.calls.takeLast(2))
        assertEquals(3_000L, repo.switches.value.readingSince)
    }

    @Test
    fun `nothing is stored once reading is off, even by a scan already running`() = runTest {
        repo.agreeToReading(at = 500L)
        repo.stopReading()

        assertFalse(repo.take(MpesaReading.Read(record("SKA1B2C3D4"))))
        repo.take(MpesaReading.Unread(LocalDate.of(2026, 11, 12)))

        assertTrue(dao.rows.isEmpty())
        assertTrue(dao.days.isEmpty())
    }

    @Test
    fun `turning sending off during a push stops it at the next request`() = runTest {
        readingAndSending()
        (1..150).forEach { repo.take(MpesaReading.Read(record("SKA%07d".format(it)))) }
        api.onUpload = { prefs.stopSending() }

        repo.push()

        assertEquals(1, api.uploads.size)
        assertEquals(50, dao.rows.values.count { it.uploadedAt == null })
    }

    @Test
    fun `a long backlog of unread days goes in chunks the server takes`() = runTest {
        repo.agreeToReading(at = 500L)
        val first = LocalDate.of(2026, 9, 1)
        (0L until 70L).forEach { repo.take(MpesaReading.Unread(first.plusDays(it))) }
        repo.agreeToSending()

        repo.push()

        assertEquals(listOf(31, 31, 8), api.unreadSent.map { it.size })
        assertTrue(dao.days.values.all { it.syncedAt == it.updatedAt })
    }

    @Test
    fun `a note on a row the server refused is dropped, and one on a missing piece stops after a week`() = runTest {
        readingAndSending()
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        repo.take(MpesaReading.Read(record("SKA1B2C3D5")))
        repo.annotate("SKA1B2C3D4", MpesaCategory.WAGES, null, null)
        repo.annotate("SKA1B2C3D5", MpesaCategory.WAGES, "piece-gone", null)
        api.noteFor = mapOf("SKA1B2C3D4" to NoteOutcome.DROPPED, "SKA1B2C3D5" to NoteOutcome.NO_SUCH_PIECE)

        repo.push()
        repo.push()
        assertEquals(listOf("SKA1B2C3D4", "SKA1B2C3D5", "SKA1B2C3D5"), api.notes.map { it.code })

        clock += 8L * 24 * 60 * 60 * 1000
        repo.push()
        repo.push()
        assertEquals(4, api.notes.size)
    }

    @Test
    fun `a comment is cut by characters, never inside one`() = runTest {
        repo.agreeToReading(at = 500L)
        repo.take(MpesaReading.Read(record("SKA1B2C3D4")))
        val emoji = "🪵"          // one character, two UTF-16 units

        repo.annotate("SKA1B2C3D4", null, null, emoji.repeat(600))

        val comment = dao.rows.values.single().comment!!
        assertEquals(500, comment.codePointCount(0, comment.length))
        assertEquals(emoji.repeat(500), comment)
    }

    @Test
    fun `the screens see each transaction with its note`() = runTest {
        repo.agreeToReading(at = 500L)
        repo.take(MpesaReading.Read(record("SKA1B2C3D4", MpesaDirection.IN)))
        repo.annotate("SKA1B2C3D4", MpesaCategory.CUSTOMER_PAYMENT, "piece-1", "deposit")

        val entry = repo.observeEntries().first().single()
        assertEquals(MpesaDirection.IN, entry.record.direction)
        assertEquals(MpesaCategory.CUSTOMER_PAYMENT, entry.category)
        assertEquals("piece-1", entry.pieceId)
        assertEquals("deposit", entry.comment)
    }
}

private class FakeMpesaApi : MpesaApi {
    var reachable = true
    var consent: ConsentOutcome = ConsentOutcome.Agreed
    var upload: UploadOutcome? = null
    var note: NoteOutcome = NoteOutcome.SAVED
    var noteFor: Map<String, NoteOutcome> = emptyMap()
    var onUpload: (() -> Unit)? = null
    val uploads = mutableListOf<List<MpesaTransactionEntity>>()
    val notes = mutableListOf<MpesaTransactionEntity>()
    val unreadSent = mutableListOf<List<MpesaUnreadDayEntity>>()
    val agreed = mutableListOf<String>()
    val withdrawn = mutableListOf<String>()
    val calls = mutableListOf<String>()

    override suspend fun upload(rows: List<MpesaTransactionEntity>): UploadOutcome {
        if (!reachable) return UploadOutcome.Later
        uploads += rows
        onUpload?.invoke()
        return upload ?: UploadOutcome.Done(rows.map { it.code }, emptyList())
    }

    override suspend fun annotate(row: MpesaTransactionEntity): NoteOutcome {
        if (!reachable) return NoteOutcome.LATER
        notes += row
        return noteFor[row.code] ?: note
    }

    override suspend fun unread(days: List<MpesaUnreadDayEntity>, parserVersion: Int): Boolean? {
        if (!reachable) return false
        unreadSent += days
        return true
    }

    override suspend fun agree(kind: String): ConsentOutcome {
        if (!reachable) return ConsentOutcome.Offline
        calls += "agree $kind"
        if (consent == ConsentOutcome.Agreed) agreed += kind
        return consent
    }

    override suspend fun withdraw(kind: String): Boolean {
        if (!reachable) return false
        calls += "withdraw $kind"
        withdrawn += kind
        return true
    }
}

/** The two tables in memory, with Room's conflict rules where the repository relies on them. */
private class FakeMpesaDao : MpesaDao {
    private val table = MutableStateFlow<Map<String, MpesaTransactionEntity>>(emptyMap())
    val rows: Map<String, MpesaTransactionEntity> get() = table.value
    val days = mutableMapOf<String, MpesaUnreadDayEntity>()

    private fun update(code: String, change: (MpesaTransactionEntity) -> MpesaTransactionEntity) {
        table.value[code]?.let { table.value = table.value + (code to change(it)) }
    }

    override suspend fun insertIfNew(transaction: MpesaTransactionEntity): Long {
        if (transaction.code in table.value) return -1L
        table.value = table.value + (transaction.code to transaction)
        return table.value.size.toLong()
    }

    override fun observeAll(): Flow<List<MpesaTransactionEntity>> =
        table.map { it.values.sortedByDescending { row -> row.occurredAt } }

    override fun observe(code: String): Flow<MpesaTransactionEntity?> = table.map { it[code] }

    override suspend fun annotate(code: String, category: String?, pieceId: String?, comment: String?, at: Long) =
        update(code) { it.copy(category = category, pieceId = pieceId, comment = comment, annotatedAt = at) }

    override suspend fun notUploaded(limit: Int) =
        table.value.values.filter { it.uploadedAt == null }.sortedBy { it.occurredAt }.take(limit)

    override suspend fun markUploaded(codes: List<String>, at: Long) =
        codes.forEach { code -> update(code) { it.copy(uploadedAt = at) } }

    override suspend fun unsyncedAnnotations() = table.value.values.filter {
        it.uploadedAt != null && it.annotatedAt != null &&
            (it.annotationSyncedAt == null || it.annotationSyncedAt!! < it.annotatedAt!!)
    }

    override suspend fun markAnnotationSynced(code: String, annotatedAt: Long) =
        update(code) { if (it.annotatedAt == annotatedAt) it.copy(annotationSyncedAt = annotatedAt) else it }

    override suspend fun unreadDay(day: String) = days[day]

    override suspend fun putUnreadDay(day: MpesaUnreadDayEntity) {
        days[day.day] = day
    }

    override suspend fun unsyncedUnreadDays() =
        days.values.filter { it.syncedAt == null || it.syncedAt!! < it.updatedAt }.sortedBy { it.day }

    override suspend fun markUnreadDaySynced(day: String, updatedAt: Long) {
        days[day]?.takeIf { it.updatedAt == updatedAt }?.let { days[day] = it.copy(syncedAt = updatedAt) }
    }
}

/** SharedPreferences in memory, as LocationPreferenceTest keeps them. */
private class FakePrefs : SharedPreferences {
    private val values = mutableMapOf<String, Any?>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()
    override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
        (values[key] as Set<String>?)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
    override fun contains(key: String?) = values.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor()
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    private inner class Editor : SharedPreferences.Editor {
        private val changes = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            changes[key!!] = value
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor {
            removals += key!!
            return this
        }

        override fun clear(): SharedPreferences.Editor {
            clearAll = true
            return this
        }

        override fun commit(): Boolean {
            if (clearAll) values.clear()
            removals.forEach { values.remove(it) }
            values.putAll(changes)
            return true
        }

        override fun apply() {
            commit()
        }
    }
}
