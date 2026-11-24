package com.qualityverifier.shop

import android.os.SystemClock
import android.util.Log
import com.qualityverifier.data.shop.ShopTabletWipe
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
    /**
     * Whether the handset is a shop's tablet: `AppContainer.shopMode`. Not "signed in to a
     * shop account": the server can refuse the refresh token with a customer's verdict on the
     * screen, and the tablet must go on wiping and hiding the screen after that.
     */
    private val isShopTablet: () -> Boolean,
    private val tablet: ShopTabletWipe,
    /** Drops the decoded photos the image loader holds in memory. */
    private val clearPhotoCache: () -> Unit,
    /** Outlives every screen, so a wipe started as the app leaves the screen finishes. */
    private val scope: CoroutineScope,
    clock: () -> Long = SystemClock::elapsedRealtime,
) {
    /** The five minutes with no touch. Fed by MainActivity's touch handler and every text field. */
    val idle = IdleWatch(IDLE_MILLIS, clock)

    private val _resets = MutableStateFlow(0)

    /**
     * Counts every end. The navigation watches it and goes back to an empty start screen,
     * which also locks the owner's controls again.
     */
    val resets: StateFlow<Int> = _resets.asStateFlow()

    /** True on a shop's tablet. Read at the moment it matters: a sign-in can change it. */
    val active: Boolean get() = isShopTablet()

    /**
     * Set as the activity is destroyed: true only when it is destroyed to be built again at
     * once for a rotation (or another configuration change: a resize, the language), in this
     * same process. See [restoresSavedScreens].
     */
    @Volatile private var recreatingForRotation = false

    fun activityDestroyed(changingConfigurations: Boolean) {
        recreatingForRotation = changingConfigurations
    }

    /**
     * Whether the activity being created may restore the screens Android saved for it. Read
     * once per creation; it forgets the rotation it answers for.
     */
    fun mayRestoreScreens(): Boolean {
        val rotation = recreatingForRotation
        recreatingForRotation = false
        return restoresSavedScreens(shopTablet = active, recreatedForRotation = rotation)
    }

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
                val wiped = tablet.wipe(end)
                if (wiped > 0) Log.i(TAG, "Deleted the tablet's copy of $wiped assessment(s): ${end.id}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "The tablet could not delete its copy", e)
            }
            // The decoded photos held in memory are the last copy outside the files.
            runCatching { clearPhotoCache() }
        }
    }

    /**
     * At process start: anything left on a shop's tablet is from a process that died before
     * it could wipe (a crash, or the battery), so it goes now, as if the customer had left.
     */
    fun clearLeftovers() {
        if (!active) return
        scope.launch {
            runCatching { tablet.wipe(TabletEnd.LEFT_APP) }
            runCatching { tablet.flush() }
        }
    }

    private companion object {
        const val TAG = "ShopMode"
        const val IDLE_MILLIS = 5L * 60 * 1000
    }
}

/**
 * Whether the screens Android saved may come back (row 12). Android saves the open screen, an
 * assessment included, when the app leaves the screen, before the end that leaving causes
 * has brought back the start screen. After the process dies in the background, it would open
 * the next customer on the earlier customer's assessment. So a shop's tablet restores saved
 * screens only across a rotation, which happens within one customer's assessment; anything
 * else starts at the empty start screen. A personal phone restores as it always has.
 */
fun restoresSavedScreens(shopTablet: Boolean, recreatedForRotation: Boolean): Boolean =
    !shopTablet || recreatedForRotation

/**
 * Whether the screen stays on while an assessment is open (row 12). On a shop's tablet the
 * screen locking is one of the four ends, so a short screen timeout would delete a customer's
 * assessment while they wait for the verdict. The five idle minutes are the tablet's timeout
 * instead. A personal phone keeps its own settings.
 */
fun keepsScreenOn(shopTablet: Boolean): Boolean = shopTablet

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
