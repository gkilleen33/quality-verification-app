package com.qualityverifier.tracker.data

import android.util.Log
import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaDirection
import com.qualityverifier.domain.MpesaType
import com.qualityverifier.mpesa.MpesaEntry
import com.qualityverifier.mpesa.MpesaRecord
import com.qualityverifier.mpesa.NotificationMessage
import com.qualityverifier.mpesa.TrackerIntake
import com.qualityverifier.mpesa.TrackerReading
import com.qualityverifier.mpesa.TrackerRecord
import com.qualityverifier.text.sha256Hex
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A transaction with what the person said about it, and the network it came from. */
data class TrackerEntry(val network: MobileMoneyNetwork, val entry: MpesaEntry)

/** What storing one message came to. */
enum class TakeOutcome {
    /** A transaction this phone did not hold. Worth a push. */
    STORED,

    /** Already held, or a count already made: the same message read again. */
    ALREADY_HELD,

    /** Counted as unread for its day. */
    COUNTED,

    /** Not stored and not counted: reading off, before the agreement, or no money moved. */
    NOTHING,
}

/**
 * PesaLog's records on the phone: what the listener stores, what the log shows, the two
 * questions, the push to the server, and deleting the server's copy.
 *
 * The same shape as Fundi Bora's MpesaRepository, which is not reused because it is bound to
 * Fundi Bora's tables and its routes, and reads M-Pesa only.
 */
class TrackerRepository(
    private val dao: TrackerDao,
    private val prefs: TrackerPreference,
    private val api: TrackerApi,
    private val now: () -> Long = System::currentTimeMillis,
) {

    val switches: StateFlow<TrackerSwitches> = prefs.switches
    val listener: StateFlow<ListenerStatus> = prefs.listener

    // ---- reading

    /**
     * Stores what one notification message amounts to (TrackerIntake). Nothing is stored or
     * counted once reading is off: the switch is read again here, for every message, so a
     * listener callback that began before the switch moved stores nothing after it.
     */
    suspend fun take(message: NotificationMessage): TakeOutcome {
        val since = prefs.readingSince ?: return TakeOutcome.NOTHING
        val at = now()
        return when (val reading = TrackerIntake.read(message, since)) {
            is TrackerReading.Read -> {
                prefs.messageFound(at)
                if (dao.insertIfNew(reading.record.toEntity(at)) != -1L) TakeOutcome.STORED else TakeOutcome.ALREADY_HELD
            }
            is TrackerReading.Unread -> {
                prefs.messageFound(at)
                // The same message, seen again in an updated or reposted notification, counts once.
                val key = sha256Hex("${message.network.id}|${message.receivedAt}|${message.text}".toByteArray())
                dao.pruneCounted(before = at - COUNTED_KEEP_MILLIS)
                val counted = dao.countUnread(reading.network.id, reading.day.toString(), CountedUnreadEntity(key, at), at)
                if (counted) TakeOutcome.COUNTED else TakeOutcome.ALREADY_HELD
            }
            TrackerReading.Ignored, TrackerReading.NotMoney -> TakeOutcome.NOTHING
        }
    }

    fun listenerConnected() = prefs.listenerConnected(now())
    fun listenerDisconnected() = prefs.listenerDisconnected(now())

    // ---- the screens

    fun observeEntries(): Flow<List<TrackerEntry>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toEntry() } }

    fun observeEntry(network: String, code: String): Flow<TrackerEntry?> = dao.observe(network, code).map { it?.toEntry() }

    /**
     * Records what the person says a transaction was for. Local first; the server hears on
     * the next push, if sending is on. The screen offers only the categories the direction
     * allows (MpesaCategory.choicesFor), which the server holds too.
     */
    suspend fun annotate(network: String, code: String, category: MpesaCategory?, comment: String?) {
        // Cut by characters as the server counts them (code points), never inside a pair.
        val text = comment?.trim()?.takeIf { it.isNotEmpty() }?.let {
            if (it.codePointCount(0, it.length) <= MAX_COMMENT) it else it.substring(0, it.offsetByCodePoints(0, MAX_COMMENT))
        }
        dao.annotate(network, code, category?.id, text, now())
    }

    // ---- the two questions

    /** Agrees to reading, and reads from [at], the moment the person said yes. */
    suspend fun agreeToReading(at: Long): ConsentOutcome {
        // A withdrawal made offline goes first, so the server's newest row is this agreement.
        flushWithdrawals()
        val outcome = api.agree(TrackerPreference.READ)
        if (outcome == ConsentOutcome.Agreed) prefs.startReading(at)
        return outcome
    }

    /** Agrees to sending. Only after reading: the server refuses it otherwise. */
    suspend fun agreeToSending(): ConsentOutcome {
        if (!prefs.switches.value.reading) return ConsentOutcome.Failed
        flushWithdrawals()
        val outcome = api.agree(TrackerPreference.SEND)
        if (outcome == ConsentOutcome.Agreed) prefs.startSending()
        return outcome
    }

    /** Stops reading and sending on this phone at once; the server hears when it can. */
    suspend fun stopReading() {
        prefs.stopReading()
        flushWithdrawals()
    }

    /** Stops sending on this phone at once; the server hears when it can. */
    suspend fun stopSending() {
        prefs.stopSending()
        flushWithdrawals()
    }

    /**
     * After a sign-in on a phone that has no answers recorded: takes the server's word for
     * what this account already agreed to elsewhere, so the questions are not asked again.
     * Reading counts only messages that arrive from now on, here, since nothing older was
     * agreed to on this phone.
     */
    suspend fun adoptServerAnswers(): QuestionAnswers? {
        val answers = api.answers() ?: return null
        if (answers.readAgreed && !prefs.switches.value.reading) prefs.startReading(now())
        if (answers.sendAgreed && prefs.switches.value.reading && !prefs.sending) prefs.startSending()
        return answers
    }

    /** Whether the account just signed in is one PesaLog can use. */
    suspend fun accountCheck(): AccountCheck = api.accountCheck()

    // ---- the push

    private val pushing = Mutex()

    /**
     * Sends what the phone owes: withdrawals, then, while sending is on, new transactions,
     * notes on transactions the server holds, and unread counts. Everything not confirmed
     * stays and is tried again at the next push. One push at a time: the listener and the
     * screens can each start one. The sending switch is read again before every request.
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
                    val at = now()
                    done.forEach { dao.markUploaded(it.network, it.code, at) }
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
                    dao.markAnnotationSynced(row.network, row.code, annotatedAt)
                    confirmed++
                }
                NoteOutcome.DROPPED -> dao.markAnnotationSynced(row.network, row.code, annotatedAt)
                NoteOutcome.LATER -> Unit
                NoteOutcome.SENDING_REFUSED -> return@withLock confirmed.also { serverRefusedSending() }
            }
        }

        for (days in dao.unsyncedUnreadDays().chunked(UNREAD_ROWS_PER_REQUEST)) {
            if (!prefs.sending) break
            when (api.unread(days, TrackerIntake.PARSER_VERSION)) {
                true -> {
                    days.forEach { dao.markUnreadDaySynced(it.network, it.day, it.updatedAt) }
                    confirmed += days.size
                }
                null -> return@withLock confirmed.also { serverRefusedSending() }
                false -> break
            }
        }
        confirmed
    }

    // ---- deletion

    /**
     * Deletes every record PesaLog sent from this account on the server (terms section 8).
     * The server withdraws sending in the same step, so sending stops here too, with nothing
     * more to tell it. The rows on this phone stay, marked as sent, so turning sending on
     * again later does not send them back.
     */
    suspend fun deleteFromServer(): DeleteOutcome = pushing.withLock {
        val outcome = api.deleteRecords()
        if (outcome is DeleteOutcome.Deleted) prefs.stopSending(record = false)
        outcome
    }

    /** Sends the withdrawals made on this phone that the server has not recorded. */
    private suspend fun flushWithdrawals() {
        // Reading first: on the server it withdraws sending as well.
        for (kind in listOf(TrackerPreference.READ, TrackerPreference.SEND)) {
            if (kind in prefs.pendingWithdrawals && api.withdraw(kind)) prefs.withdrawalRecorded(kind)
        }
    }

    /** The server holds no agreement to sending: withdrawn on another phone, or never recorded. */
    private fun serverRefusedSending() {
        Log.i(TAG, "Server holds no agreement to sending; sending turned off on this phone")
        prefs.stopSending(record = false)
    }

    private companion object {
        const val TAG = "TrackerRepository"

        /** Transactions per upload. The server takes up to 500. */
        const val BATCH = 100

        /** V33's tracker_transactions_comment_length. */
        const val MAX_COMMENT = 500

        /** Unread rows per request. The server takes up to 124. */
        const val UNREAD_ROWS_PER_REQUEST = 60

        /** How long a counted message's key is kept: longer than a notification stays up. */
        const val COUNTED_KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000
    }
}

private fun TrackerRecord.toEntity(storedAt: Long) = TransactionEntity(
    network = network.id,
    code = record.code,
    direction = record.direction.id,
    type = record.type.id,
    amountKes = record.amountKes,
    feeKes = record.feeKes,
    balanceAfterKes = record.balanceAfterKes,
    occurredAt = record.occurredAt,
    counterpartyName = record.counterpartyName,
    counterpartyPhone = record.counterpartyPhone,
    parserVersion = TrackerIntake.PARSER_VERSION,
    storedAt = storedAt,
)

/** Null for a row a later build wrote with a vocabulary this one does not know. */
private fun TransactionEntity.toEntry(): TrackerEntry? {
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
    val network = MobileMoneyNetwork.fromId(network) ?: return null
    return TrackerEntry(network, MpesaEntry(record, MpesaCategory.fromId(category), pieceId = null, comment = comment))
}
