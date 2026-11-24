package com.qualityverifier.data.shop

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.qualityverifier.data.sync.TabletEndApi
import com.qualityverifier.domain.TabletEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Kagua on a shop's tablet keeps no customer's assessment (release scope row 12; server V39).
 *
 * When the customer presses "Done: delete this assessment", leaves the app, locks the screen,
 * or stops touching it for five minutes, [wipe] deletes the tablet's copy of everything: every
 * assessment row, every message and verdict, and every photo file the app wrote. Then it tells
 * the server which of the four it was, for research.
 *
 * Local only. It never sends `DELETE /v1/sessions/{id}`: that marks an assessment deleted by
 * its owner, and V11's purge removes it seven days later, when the server must keep every
 * shop assessment. Nothing here can reach that call: [api] reports an end and does nothing else.
 *
 * Everything rather than one assessment, because a shop's tablet holds one customer at a time
 * and nothing it holds is worth keeping past them: an abandoned capture or a half-sent turn is
 * the same customer's. The server already sends a shop account nothing back (V39).
 */
class ShopTabletWipe(
    /** Every assessment the tablet holds, by id. */
    private val localSessionIds: suspend () -> Collection<String>,
    /**
     * Deletes every local assessment and every photo file. In the app, every Room table and
     * the whole image folder (see AppContainer).
     */
    private val clearLocal: suspend () -> Unit,
    private val pending: TabletEndQueue,
    private val api: TabletEndApi,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One wipe at a time: leaving the app and the idle timer can fire together. */
    private val lock = Mutex()

    /**
     * Deletes the tablet's copy of every assessment, records [end] against each for the
     * server, and tries to send it. Returns how many assessments were deleted; zero when the
     * tablet held none, in which case nothing is reported.
     */
    suspend fun wipe(end: TabletEnd): Int {
        val wiped = lock.withLock {
            val ids = localSessionIds().toList()
            // Queued before the clear, so a crash between the two loses the photos, not the
            // record of how they went.
            ids.forEach { pending.add(PendingTabletEnd(it, end, now())) }
            clearLocal()
            ids.size
        }
        flush()
        return wiped
    }

    /**
     * Sends every end not yet on the server, best effort. Returns how many went. Ones the
     * server cannot place yet are kept for a day, then dropped: an assessment whose first turn
     * never arrived has nothing to record against.
     */
    suspend fun flush(): Int {
        var sent = 0
        for (item in pending.all()) {
            val done = try {
                api.reportTabletEnd(item.sessionId, item.end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not report a tablet end; kept", e)
                false
            }
            if (done) {
                pending.remove(item.sessionId)
                sent++
            } else if (now() - item.at > KEEP_MILLIS) {
                pending.remove(item.sessionId)
            }
        }
        return sent
    }

    private companion object {
        const val TAG = "ShopTabletWipe"
        const val KEEP_MILLIS = 24L * 60 * 60 * 1000
    }
}

/** How one assessment's tablet copy ended, waiting to reach the server. No customer content. */
data class PendingTabletEnd(val sessionId: String, val end: TabletEnd, val at: Long)

/** The ends not yet on the server. Kept outside Room, so the wipe that records them cannot clear them. */
interface TabletEndQueue {
    fun add(item: PendingTabletEnd)
    fun all(): List<PendingTabletEnd>
    fun remove(sessionId: String)
}

/**
 * Whether this handset is a shop's tablet (row 12): the last account signed in on it was a shop
 * account. Written from the sign-in answer and every profile read, through
 * [com.qualityverifier.data.auth.TokenStore.setShopMode].
 *
 * Kept apart from the tokens, in plain shared preferences, so that clearing them does not clear
 * it. The server refuses a refresh token (expiry, a device revoked in the portal, reuse
 * detection) whenever it likes, and the phone answers by clearing the tokens, possibly with a
 * customer's verdict on the screen. If that also forgot the tablet was a shop's, the wipe and
 * `FLAG_SECURE` would stop at that moment, and the customer's photos and verdict would stay on
 * the tablet for the next customer. So only the next sign-in changes it: a personal account
 * signing in on the handset sets it false. An unreadable token file (a cleared Keystore) cannot
 * take it either. Nothing in it is secret: a yes or a no.
 */
class ShopTabletFlag(private val prefs: SharedPreferences) {
    // The app's constructor. The one taking the prefs directly lets plain JVM tests run.
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(SHOP_TABLET_PREFS, Context.MODE_PRIVATE),
    )

    val isShopTablet: Boolean get() = prefs.getBoolean(KEY, false)

    @SuppressLint("ApplySharedPref")
    fun set(value: Boolean) {
        // commit(), as the tokens are: the next screen reads it straight back.
        if (isShopTablet != value) prefs.edit().putBoolean(KEY, value).commit()
    }

    private companion object {
        const val KEY = "is_shop_tablet"
    }
}

/** The shop tablet's own small file: the flag above and the queue of ends below. */
private const val SHOP_TABLET_PREFS = "kagua_shop_tablet"

/**
 * [TabletEndQueue] in plain shared preferences: a session id, one of four words, and a time,
 * none of which is about the customer.
 */
class PrefsTabletEndQueue(context: Context) : TabletEndQueue {
    private val prefs = context.applicationContext.getSharedPreferences(SHOP_TABLET_PREFS, Context.MODE_PRIVATE)

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun add(item: PendingTabletEnd) {
        // The first end of an assessment stands, as it does on the server.
        if (all().any { it.sessionId == item.sessionId }) return
        prefs.edit().putStringSet(KEY, raw() + "${item.sessionId}|${item.end.id}|${item.at}").commit()
    }

    @Synchronized
    override fun all(): List<PendingTabletEnd> = raw().mapNotNull { line ->
        val parts = line.split('|')
        val end = TabletEnd.fromId(parts.getOrNull(1)) ?: return@mapNotNull null
        val at = parts.getOrNull(2)?.toLongOrNull() ?: return@mapNotNull null
        PendingTabletEnd(parts[0], end, at)
    }

    @Synchronized
    @SuppressLint("ApplySharedPref")
    override fun remove(sessionId: String) {
        prefs.edit().putStringSet(KEY, raw().filterNot { it.startsWith("$sessionId|") }.toSet()).commit()
    }

    private fun raw(): Set<String> = prefs.getStringSet(KEY, null)?.toSet().orEmpty()

    private companion object {
        const val KEY = "pending_ends"
    }
}
