package com.qualityverifier.data.mpesa

import android.util.Log
import com.qualityverifier.data.db.MpesaDao
import com.qualityverifier.data.db.MpesaTransactionEntity
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import com.qualityverifier.mpesa.MpesaEntry
import com.qualityverifier.mpesa.MpesaIntake
import com.qualityverifier.mpesa.MpesaReading
import com.qualityverifier.mpesa.MpesaRecord
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The two M-Pesa answers, as the questions and the Settings switches change them. */
interface MpesaAnswers {
    val switches: StateFlow<MpesaSwitches>
    suspend fun agreeToReading(at: Long): ConsentOutcome
    suspend fun agreeToSending(): ConsentOutcome
    suspend fun stopReading()
    suspend fun stopSending()
}

/**
 * Fundi Bora's M-Pesa records on the phone: what the reader stores, what the Finances tab
 * shows, the two consents, and the push to the server.
 *
 * In `:core` beside the roster for the reason [com.qualityverifier.data.workers.WorkerRepository]
 * is: the push rides the same sync and the same token provider as everything else. Kagua
 * constructs it and never calls it; nothing is read until a fundi agrees on Fundi Bora.
 */
class MpesaRepository(
    private val dao: MpesaDao,
    private val prefs: MpesaPreference,
    private val api: MpesaApi,
    private val now: () -> Long = System::currentTimeMillis,
) : MpesaAnswers {

    override val switches: StateFlow<MpesaSwitches> = prefs.switches

    // ---- reading

    /**
     * Where the inbox scan starts, by the SMS store's receive time: the later of the
     * agreement and the last scan. Null while reading is off, when nothing is scanned.
     */
    val scanFrom: Long? get() = if (prefs.readingSince == null) null else prefs.scanWatermark

    /** The scan has read every message received up to [date]. */
    fun scannedUpTo(date: Long) {
        if (prefs.readingSince != null && date > prefs.scanWatermark) prefs.scanWatermark = date
    }

    /**
     * Stores what one message amounts to. Returns true when it was a transaction this phone
     * did not hold yet, which is when a push is worth starting.
     *
     * Nothing is stored once reading is off, even by a scan or a receiver that started
     * before the switch moved: the switch is read again here, for every message.
     */
    suspend fun take(reading: MpesaReading): Boolean {
        if (!prefs.switches.value.reading) return false
        return when (reading) {
            is MpesaReading.Read -> dao.insertIfNew(reading.record.toEntity(now())) != -1L
            is MpesaReading.Unread -> {
                dao.countUnread(reading.day.toString(), now())
                false
            }
            MpesaReading.Ignored, MpesaReading.NotMoney -> false
        }
    }

    // ---- the screens

    fun observeEntries(): Flow<List<MpesaEntry>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toEntry() } }

    fun observeEntry(code: String): Flow<MpesaEntry?> = dao.observe(code).map { it?.toEntry() }

    /**
     * Records what the fundi says a transaction was for. Local first; the server hears on the
     * next push, if sending is on. The screen offers only the categories the transaction's
     * direction allows (MpesaCategory.choicesFor), which is the rule the server holds too.
     */
    suspend fun annotate(code: String, category: MpesaCategory?, pieceId: String?, comment: String?) {
        // Cut by characters as the server counts them (code points), never inside a pair.
        val text = comment?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.codePointCount(0, it.length) <= MAX_COMMENT) it else it.substring(0, it.offsetByCodePoints(0, MAX_COMMENT))
        }
        dao.annotate(code, category?.id, pieceId, text, now())
    }

    // ---- the two questions

    /** Agrees to reading, and starts it from [at], the moment the fundi said yes. */
    override suspend fun agreeToReading(at: Long): ConsentOutcome {
        // A withdrawal made offline goes first, so the server's newest row is this agreement.
        flushWithdrawals()
        val outcome = api.agree(MpesaPreference.READ)
        if (outcome == ConsentOutcome.Agreed) prefs.startReading(at)
        return outcome
    }

    /** Agrees to sending. Only after reading: the server refuses it otherwise. */
    override suspend fun agreeToSending(): ConsentOutcome {
        if (!prefs.switches.value.reading) return ConsentOutcome.Failed
        flushWithdrawals()
        val outcome = api.agree(MpesaPreference.SEND)
        if (outcome == ConsentOutcome.Agreed) prefs.startSending()
        return outcome
    }

    /** Stops reading and sending on this phone at once; the server hears when it can. */
    override suspend fun stopReading() {
        prefs.stopReading()
        flushWithdrawals()
    }

    /** Stops sending on this phone at once; the server hears when it can. */
    override suspend fun stopSending() {
        prefs.stopSending()
        flushWithdrawals()
    }

    // ---- the push

    private val pushing = Mutex()

    /**
     * Sends what the phone owes: withdrawals, then, while sending is on, new transactions,
     * then notes on transactions the server holds, then unread counts. Everything not
     * confirmed stays and is tried again at the next push, so a failure costs nothing.
     * One push at a time: the receiver and the sync can both start one.
     *
     * The sending switch is read again before every request, so turning it off part-way
     * through stops the push at the next request, whether or not the withdrawal has reached
     * the server yet.
     *
     * Returns how many rows the server confirmed.
     */
    suspend fun push(): Int = pushing.withLock {
        flushWithdrawals()
        var confirmed = 0

        while (prefs.sending) {
            val batch = dao.notUploaded(BATCH)
            if (batch.isEmpty()) break
            when (val outcome = api.upload(batch)) {
                is UploadOutcome.Done -> {
                    // A refused row is marked too: it would be refused again, and leaving it
                    // would put it at the head of every batch for ever.
                    val done = outcome.accepted + outcome.rejected
                    if (done.isEmpty()) break
                    dao.markUploaded(done, now())
                    confirmed += outcome.accepted.size
                    if (outcome.rejected.isNotEmpty()) Log.w(TAG, "Server refused ${outcome.rejected.size} transactions")
                }
                UploadOutcome.SendingRefused -> return@withLock confirmed.also { serverRefusedSending() }
                UploadOutcome.Later -> return@withLock confirmed
            }
            if (batch.size < BATCH) break
        }

        for (row in dao.unsyncedAnnotations()) {
            if (!prefs.sending) return@withLock confirmed
            val annotatedAt = row.annotatedAt!!
            when (api.annotate(row)) {
                NoteOutcome.SAVED -> {
                    dao.markAnnotationSynced(row.code, annotatedAt)
                    confirmed++
                }
                NoteOutcome.DROPPED -> dao.markAnnotationSynced(row.code, annotatedAt)
                // The piece's assessment usually reaches the server soon after. One that never
                // does (deleted on the phone first) would be retried at every sync, so the
                // note stops being sent once it is a week old; it stays on the phone.
                NoteOutcome.NO_SUCH_PIECE ->
                    if (now() - annotatedAt > PIECE_WAIT_MILLIS) dao.markAnnotationSynced(row.code, annotatedAt)
                NoteOutcome.LATER -> Unit
                NoteOutcome.SENDING_REFUSED -> return@withLock confirmed.also { serverRefusedSending() }
            }
        }

        // In chunks the server takes (it refuses more than 62 days in one request), so a long
        // spell with sending off cannot leave a backlog that is refused for ever.
        for (days in dao.unsyncedUnreadDays().chunked(UNREAD_DAYS_PER_REQUEST)) {
            if (!prefs.sending) break
            when (api.unread(days, MpesaIntake.PARSER_VERSION)) {
                true -> {
                    days.forEach { dao.markUnreadDaySynced(it.day, it.updatedAt) }
                    confirmed += days.size
                }
                null -> return@withLock confirmed.also { serverRefusedSending() }
                false -> break
            }
        }
        confirmed
    }

    /** Sends the withdrawals made on this phone that the server has not recorded. */
    private suspend fun flushWithdrawals() {
        // Reading first: on the server it withdraws sending as well.
        for (kind in listOf(MpesaPreference.READ, MpesaPreference.SEND)) {
            if (kind in prefs.pendingWithdrawals && api.withdraw(kind)) prefs.withdrawalRecorded(kind)
        }
    }

    /**
     * The server has no agreement to sending: withdrawn on another phone, or never recorded.
     * Sending stops here too, with nothing to tell the server.
     */
    private fun serverRefusedSending() {
        Log.i(TAG, "Server holds no agreement to sending; sending turned off on this phone")
        prefs.stopSending(record = false)
    }

    private companion object {
        const val TAG = "MpesaRepository"

        /** Transactions per upload. The server takes up to 500. */
        const val BATCH = 100

        /** V29's mpesa_transactions_comment_length. */
        const val MAX_COMMENT = 500

        /** Unread days per request. The server takes up to 62. */
        const val UNREAD_DAYS_PER_REQUEST = 31

        /** How long a note naming a piece the server lacks keeps being sent. */
        const val PIECE_WAIT_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

private fun MpesaRecord.toEntity(storedAt: Long) = MpesaTransactionEntity(
    code = code,
    direction = direction.id,
    type = type.id,
    amountKes = amountKes,
    feeKes = feeKes,
    balanceAfterKes = balanceAfterKes,
    occurredAt = occurredAt,
    counterpartyName = counterpartyName,
    counterpartyPhone = counterpartyPhone,
    parserVersion = MpesaIntake.PARSER_VERSION,
    storedAt = storedAt,
)

/** Null for a row a later build wrote with a vocabulary this one does not know. */
private fun MpesaTransactionEntity.toEntry(): MpesaEntry? {
    val record = MpesaRecord(
        code = code,
        direction = MpesaDirection.fromId(direction) ?: return null,
        type = MpesaType.fromId(type) ?: return null,
        amountKes = amountKes,
        feeKes = feeKes,
        balanceAfterKes = balanceAfterKes,
        occurredAt = occurredAt,
        counterpartyName = counterpartyName,
        counterpartyPhone = counterpartyPhone,
    )
    return MpesaEntry(record, MpesaCategory.fromId(category), pieceId, comment)
}
