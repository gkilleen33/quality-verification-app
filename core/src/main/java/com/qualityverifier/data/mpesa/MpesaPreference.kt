package com.qualityverifier.data.mpesa

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two answers as this phone applies them. */
data class MpesaSwitches(
    /** When the fundi agreed to reading, on this phone's clock; null while reading is off. */
    val readingSince: Long?,
    val sending: Boolean,
) {
    val reading: Boolean get() = readingSince != null
}

/**
 * Fundi Bora's two M-Pesa answers on this phone, and what the server still has to hear.
 *
 * Plain SharedPreferences, as [com.qualityverifier.data.location.LocationPreference] is: an
 * answer and a time, nothing secret. One person's answers, so a different account signing
 * in forgets them (AppContainer's LocalAccountGuard), with the database they apply to.
 *
 * **Turning off works at once, online or not.** The server records agreements and
 * withdrawals (V29), but a fundi who says stop must not wait for a signal: reading or
 * sending stops on this phone the moment the switch moves, and the withdrawal waits in
 * [pendingWithdrawals] for the next sync. Turning on needs the server, because the
 * agreement has to be on record before anything is read.
 */
class MpesaPreference internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _switches = MutableStateFlow(read())

    /** The current answers, for screens that redraw when they change. */
    val switches: StateFlow<MpesaSwitches> = _switches.asStateFlow()

    val readingSince: Long? get() = _switches.value.readingSince
    val sending: Boolean get() = _switches.value.sending

    /**
     * How far the inbox scan has read, by the SMS store's own receive time. Never earlier
     * than [readingSince], so a scan cannot reach a message from before the agreement.
     */
    var scanWatermark: Long
        get() = maxOf(prefs.getLong(KEY_SCAN_WATERMARK, 0L), readingSince ?: Long.MAX_VALUE)
        set(value) {
            prefs.edit().putLong(KEY_SCAN_WATERMARK, value).apply()
        }

    /** `mpesa_read` and `mpesa_send` withdrawals made on this phone that the server has not recorded. */
    val pendingWithdrawals: Set<String>
        get() = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()

    /** After the server recorded the agreement to reading made at [at]. */
    fun startReading(at: Long) {
        prefs.edit()
            .putLong(KEY_READING_SINCE, at)
            .putLong(KEY_SCAN_WATERMARK, at)
            .putStringSet(KEY_PENDING, pendingWithdrawals - READ)
            .apply()
        publish()
    }

    /** Stops reading and sending at once; the server hears at the next sync. */
    fun stopReading() {
        // The server withdraws sending with reading, so one withdrawal says both.
        prefs.edit()
            .remove(KEY_READING_SINCE)
            .putBoolean(KEY_SENDING, false)
            .putStringSet(KEY_PENDING, pendingWithdrawals - SEND + READ)
            .apply()
        publish()
    }

    /** After the server recorded the agreement to sending. */
    fun startSending() {
        prefs.edit()
            .putBoolean(KEY_SENDING, true)
            .putStringSet(KEY_PENDING, pendingWithdrawals - SEND)
            .apply()
        publish()
    }

    /**
     * Stops sending at once. [record] false when the server already says sending is not
     * agreed (a 403 on upload), so there is nothing for it to hear.
     */
    fun stopSending(record: Boolean = true) {
        val pending = if (record && READ !in pendingWithdrawals) pendingWithdrawals + SEND else pendingWithdrawals
        prefs.edit()
            .putBoolean(KEY_SENDING, false)
            .putStringSet(KEY_PENDING, pending)
            .apply()
        publish()
    }

    /** The server recorded the withdrawal of [kind]. */
    fun withdrawalRecorded(kind: String) {
        prefs.edit().putStringSet(KEY_PENDING, pendingWithdrawals - kind).apply()
    }

    /**
     * Forgets every answer, for a different account signing in. commit(), for the reason
     * LocationPreference.forget gives: the next account must never start with this one's yes.
     */
    @SuppressLint("ApplySharedPref")
    fun forget() {
        prefs.edit().clear().commit()
        publish()
    }

    private fun read() = MpesaSwitches(
        readingSince = prefs.getLong(KEY_READING_SINCE, -1L).takeIf { it >= 0 },
        sending = prefs.getBoolean(KEY_SENDING, false),
    )

    private fun publish() {
        _switches.value = read()
    }

    companion object {
        const val READ = "mpesa_read"
        const val SEND = "mpesa_send"

        private const val PREFS_NAME = "fundi_mpesa"
        private const val KEY_READING_SINCE = "reading_since"
        private const val KEY_SENDING = "sending"
        private const val KEY_SCAN_WATERMARK = "scan_watermark"
        private const val KEY_PENDING = "pending_withdrawals"
    }
}
