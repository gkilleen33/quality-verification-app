package com.qualityverifier.shop

import android.content.Context
import android.os.SystemClock
import android.util.Log
import coil.imageLoader
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.TabletEnd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Kagua on a shop's tablet (release scope row 12; server V39).
 *
 * A Kagua account whose invite code was a shop-tablet code. Walk-in customers check a finished
 * piece before buying; the tablet keeps none of their assessments. Four things end a
 * customer's assessment on the tablet: "Done: delete this assessment" (or Back to the start
 * screen), leaving the app, locking the screen, and five minutes with no touch. Each one
 * deletes the tablet's copy (see `ShopTabletWipe`), tells the server which it was, and puts
 * the start screen back for the next customer. The server keeps every assessment.
 *
 * A personal Kagua account never reaches any of this: [active] is false, and every caller
 * checks it, so the app behaves as v0.6.1 did.
 */
class ShopMode(
    private val context: Context,
    private val container: AppContainer,
    /** Outlives every screen, so a wipe started as the app leaves the screen finishes. */
    private val scope: CoroutineScope,
) {
    /** The five minutes with no touch. Fed by MainActivity's touch handler. */
    val idle = IdleWatch(IDLE_MILLIS) { SystemClock.elapsedRealtime() }

    private val _resets = MutableStateFlow(0)

    /**
     * Counts every end. The navigation watches it and goes back to an empty start screen,
     * which also locks the owner's controls again.
     */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    /** True for a signed-in shop account. Read at the moment it matters: sign-in can change it. */
    val active: Boolean get() = container.isSignedIn && container.shopMode

    /**
     * Ends whatever the tablet holds, recording [end]. Does nothing for a personal account.
     * The start screen comes back at once; the files go on [scope], so leaving the app
     * cannot cut the wipe short.
     */
    fun end(end: TabletEnd) {
        if (!active) return
        _resets.update { it + 1 }
        scope.launch {
            try {
                val wiped = container.shopTablet.wipe(end)
                if (wiped > 0) Log.i(TAG, "Deleted the tablet's copy of $wiped assessment(s): ${end.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "The tablet could not delete its copy", e)
            }
            // The decoded photos Coil holds in memory are the last copy outside the files.
            runCatching { context.imageLoader.memoryCache?.clear() }
        }
    }

    /**
     * At process start: anything left on a shop's tablet is from a process that died before
     * it could wipe (a crash, or the battery), so it goes now, as if the customer had left.
     */
    fun clearLeftovers() {
        if (!active) return
        scope.launch {
            runCatching { container.shopTablet.wipe(TabletEnd.LEFT_APP) }
            runCatching { container.shopTablet.flush() }
        }
    }

    private companion object {
        const val TAG = "ShopMode"
        const val IDLE_MILLIS = 5L * 60 * 1000
    }
}

/**
 * Whether the screen has gone untouched for [timeoutMillis]. A clock in, so a test needs no
 * five-minute wait.
 */
class IdleWatch(private val timeoutMillis: Long, private val now: () -> Long) {
    @Volatile private var last = now()

    fun touched() {
        last = now()
    }

    fun isIdle(): Boolean = now() - last >= timeoutMillis
}

/**
 * Which of the two ends the app leaving the screen is. The screen going off or the lock
 * coming on is [TabletEnd.SCREEN_LOCK]; anything else that hides the app, Home or another
 * app, is [TabletEnd.LEFT_APP].
 */
fun endOnLeaving(screenInteractive: Boolean, keyguardLocked: Boolean): TabletEnd =
    if (!screenInteractive || keyguardLocked) TabletEnd.SCREEN_LOCK else TabletEnd.LEFT_APP
