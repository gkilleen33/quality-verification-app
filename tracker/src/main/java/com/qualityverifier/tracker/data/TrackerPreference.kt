package com.qualityverifier.tracker.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two answers as this phone applies them. */
data class TrackerSwitches(
    /** When the person agreed to reading, on this phone's clock; null while reading is off. */
    val readingSince: Long?,
    val sending: Boolean,
) {
    val reading: Boolean get() = readingSince != null
}

/** What the home screen says about the listener, so a person or a field officer can see it has stopped. */
data class ListenerStatus(
    /** When Android last connected the listener; null if it never has on this install. */
    val connectedAt: Long?,
    /** When Android last disconnected it, if after [connectedAt]. */
    val disconnectedAt: Long?,
    /** When the listener last found a mobile money message, read or not. */
    val lastMessageAt: Long?,
) {
    val connected: Boolean get() = connectedAt != null && (disconnectedAt == null || disconnectedAt < connectedAt)
}

/**
 * PesaLog's two answers on this phone, the withdrawals the server has still to hear, and
 * what the listener last did.
 *
 * The same rules Fundi Bora's M-Pesa answers had until v0.7.1 deleted its SMS reader (row 17):
 * turning off works at once, online or not,
 * and the withdrawal waits in [pendingWithdrawals]; turning on needs the server, because the
 * agreement has to be on record before anything is read or sent.
 */
class TrackerPreference internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
    )

    private val _switches = MutableStateFlow(readSwitches())
    val switches: StateFlow<TrackerSwitches> = _switches.asStateFlow()

    private val _listener = MutableStateFlow(readListener())
    val listener: StateFlow<ListenerStatus> = _listener.asStateFlow()

    val readingSince: Long? get() = _switches.value.readingSince
    val sending: Boolean get() = _switches.value.sending

    /** `tracker_read` and `tracker_send` withdrawals made on this phone that the server has not recorded. */
    val pendingWithdrawals: Set<String>
        get() = prefs.getStringSet(KEY_PENDING, emptySet()).orEmpty()

    /** After the server recorded the agreement to reading made at [at]. */
    fun startReading(at: Long) {
        prefs.edit()
            .putLong(KEY_READING_SINCE, at)
            .putStringSet(KEY_PENDING, pendingWithdrawals - READ)
            .apply()
        publish()
    }

    /** Stops reading and sending at once; the server hears at the next push. */
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
     * Stops sending at once. [record] false when the server already holds no agreement to
     * send (a 403 on upload, or a deletion that withdrew it), so there is nothing to tell it.
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
     * The server's last answer about PesaLog's own terms for this account: true once agreed,
     * false while they are still to agree, null before it has ever answered. Decides whether
     * PesaLog opens on home or on its terms screen.
     */
    val termsAgreed: Boolean?
        get() = if (prefs.contains(KEY_TERMS_AGREED)) prefs.getBoolean(KEY_TERMS_AGREED, false) else null

    fun rememberTerms(required: Boolean) {
        prefs.edit().putBoolean(KEY_TERMS_AGREED, !required).apply()
    }

    fun listenerConnected(at: Long) {
        prefs.edit().putLong(KEY_CONNECTED_AT, at).apply()
        _listener.value = readListener()
    }

    fun listenerDisconnected(at: Long) {
        prefs.edit().putLong(KEY_DISCONNECTED_AT, at).apply()
        _listener.value = readListener()
    }

    fun messageFound(at: Long) {
        prefs.edit().putLong(KEY_LAST_MESSAGE_AT, at).apply()
        _listener.value = readListener()
    }

    /**
     * Forgets every answer and the last message's time, for a different account signing in.
     * commit(), so it is on disk before they do. The listener's connection is the phone's,
     * not the account's, and stays.
     */
    @SuppressLint("ApplySharedPref")
    fun forget() {
        prefs.edit()
            .remove(KEY_READING_SINCE)
            .remove(KEY_SENDING)
            .remove(KEY_PENDING)
            .remove(KEY_LAST_MESSAGE_AT)
            .remove(KEY_TERMS_AGREED)
            .commit()
        publish()
        _listener.value = readListener()
    }

    private fun readSwitches() = TrackerSwitches(
        readingSince = prefs.getLong(KEY_READING_SINCE, -1L).takeIf { it >= 0 },
        sending = prefs.getBoolean(KEY_SENDING, false),
    )

    private fun readListener() = ListenerStatus(
        connectedAt = prefs.getLong(KEY_CONNECTED_AT, -1L).takeIf { it >= 0 },
        disconnectedAt = prefs.getLong(KEY_DISCONNECTED_AT, -1L).takeIf { it >= 0 },
        lastMessageAt = prefs.getLong(KEY_LAST_MESSAGE_AT, -1L).takeIf { it >= 0 },
    )

    private fun publish() {
        _switches.value = readSwitches()
    }

    companion object {
        const val READ = "tracker_read"
        const val SEND = "tracker_send"

        private const val PREFS_NAME = "pesalog"
        private const val KEY_READING_SINCE = "reading_since"
        private const val KEY_SENDING = "sending"
        private const val KEY_PENDING = "pending_withdrawals"
        private const val KEY_CONNECTED_AT = "listener_connected_at"
        private const val KEY_DISCONNECTED_AT = "listener_disconnected_at"
        private const val KEY_LAST_MESSAGE_AT = "last_message_at"
        private const val KEY_TERMS_AGREED = "terms_agreed"
    }
}
