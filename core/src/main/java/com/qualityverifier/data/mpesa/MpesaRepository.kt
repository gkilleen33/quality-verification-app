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
     */
    suspend fun take(reading: MpesaReading): Boolean = when (reading) {
        is MpesaReading.Read -> dao.insertIfNew(reading.record.toEntity(now())) != -1L
        is MpesaReading.Unread -> {
            dao.countUnread(reading.day.toString(), now())
            false
        }
        MpesaReading.Ignored, MpesaReading.NotMoney -> false
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
        val text = comment?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_COMMENT)
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
     * Returns how many rows the server confirmed.
     */
    suspend fun push(): Int = pushing.withLock {
        flushWithdrawals()
        if (!prefs.sending) return@withLock 0
        var confirmed = 0

        while (true) {
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
            when (api.annotate(row)) {
                NoteOutcome.SAVED -> {
                    dao.markAnnotationSynced(row.code, row.annotatedAt!!)
                    confirmed++
                }
                NoteOutcome.DROPPED -> dao.markAnnotationSynced(row.code, row.annotatedAt!!)
                NoteOutcome.LATER -> Unit
                NoteOutcome.SENDING_REFUSED -> return@withLock confirmed.also { serverRefusedSending() }
            }
        }

        val days = dao.unsyncedUnreadDays()
        if (days.isNotEmpty()) {
            when (api.unread(days, MpesaIntake.PARSER_VERSION)) {
                true -> {
                    days.forEach { dao.markUnreadDaySynced(it.day, it.updatedAt) }
                    confirmed += days.size
                }
                null -> serverRefusedSending()
                false -> Unit
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
