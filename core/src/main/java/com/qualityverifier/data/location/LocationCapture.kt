package com.qualityverifier.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.qualityverifier.domain.LocationFix
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume

/**
 * One GPS fix per assessment, taken without the customer doing anything.
 *
 * ## Why this is not just the cached fix
 *
 * `RegisterScreen` reads the last known position, because there the customer is standing
 * still waiting for a button to confirm and an active fix would mean holding the GPS on
 * while they watch a spinner. Here nobody is waiting: the assessment opens straight into
 * the intake questionnaire, and the photographs come after that. Minutes pass before the
 * result is needed, so this asks the providers for a real fix and lets it arrive during
 * them. A cached fix indoors is frequently absent, and when present is frequently from
 * somewhere else entirely.
 *
 * So the cached fix is used only as a floor — taken immediately, kept only if recent, and
 * replaced the moment anything better arrives. That way a fix always exists if one can,
 * and it improves for as long as the window allows.
 *
 * ## What "no delay" means here
 *
 * Nothing waits on this. [capture] is launched alongside the assessment and its result is
 * written to the session row whenever it lands; a turn sent before then carries no
 * location and the next one carries it. If the window expires with nothing, the
 * assessment is unaffected — this is not essential data and must never behave as if it
 * were.
 *
 * ## Freshness over precision
 *
 * A stale fix is more dangerous than a coarse one, because the accuracy figure makes it
 * look trustworthy: the first device test of the registration screen stored Mountain View
 * for a business in Kampala and reported it as accurate to five metres. So a cached seed
 * older than [MAX_SEED_AGE_MILLIS] is discarded outright rather than improved upon, and
 * every fix is stamped with when it was taken.
 */
class LocationCapture(
    context: Context,
    private val preference: LocationPreference,
) : LocationSource {
    private val appContext = context.applicationContext

    private val log = "LocationCapture"

    /**
     * Whether there is any point starting. Checked before launching so the common
     * refusals — switched off, permission never granted, location services off — cost
     * nothing and log nothing.
     */
    override val isAvailable: Boolean
        get() = preference.recordAtStart && hasPermission() && manager()?.let { m ->
            runCatching { m.getProviders(true).isNotEmpty() }.getOrDefault(false)
        } == true

    /**
     * The best fix obtainable within the window, or null.
     *
     * Suspends for up to [WINDOW_MILLIS]. The caller is expected to launch this and
     * forget about it — see the class comment.
     */
    override suspend fun capture(): LocationFix? {
        if (!preference.recordAtStart) return null
        if (!hasPermission()) return null
        val manager = manager() ?: return null

        val providers = runCatching { manager.getProviders(true) }.getOrDefault(emptyList())
        if (providers.isEmpty()) return null

        val seed = freshCachedFix(manager)
        val best = AtomicReference(seed)

        // Returns as soon as something good enough arrives; otherwise keeps whatever the
        // window produced, which may be the seed or nothing.
        withTimeoutOrNull(WINDOW_MILLIS) { listenForFix(manager, providers, best) }

        val fix = best.get() ?: return null
        return fix.toDomain().takeIf { it.isUsable }
    }

    /**
     * Listens on every enabled provider at once and keeps the best.
     *
     * All of them rather than one: the network provider answers in seconds and coarsely,
     * GPS takes longer and is precise, and which of those a workshop yields depends on
     * whether it has a tin roof. Racing them costs one extra listener and means the
     * coarse answer is there while the precise one is still being worked out.
     */
    @SuppressLint("MissingPermission")
    private suspend fun listenForFix(
        manager: LocationManager,
        providers: List<String>,
        best: AtomicReference<Location?>,
    ) = suspendCancellableCoroutine<Unit> { continuation ->
        // One listener across every provider, and removeUpdates deregisters it from all
        // of them at once — so this is a flag rather than a list of registrations.
        var listening = false
        var self: LocationListener? = null

        fun stopAll() {
            if (!listening) return
            listening = false
            self?.let { runCatching { manager.removeUpdates(it) } }
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (isBetter(location, best.get())) best.set(location)
                // Good enough to place a shop front. Holding the radio on past this
                // point spends the customer's battery to refine a number nobody will
                // read to that precision.
                val accurate = location.hasAccuracy() &&
                    location.accuracy <= GOOD_ENOUGH_METRES
                if (accurate && continuation.isActive) {
                    stopAll()
                    continuation.resume(Unit)
                }
            }

            // Present and empty on purpose. These three gained default implementations
            // in API 30; on 24 to 29 they are abstract, so a lambda compiled against the
            // newer interface would throw AbstractMethodError the first time the
            // framework called one. minSdk here is 24.
            @Deprecated("Required below API 30")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit
        }

        self = listener
        continuation.invokeOnCancellation { stopAll() }

        val started = providers.count { provider ->
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    // Every fix, as fast as it comes: the window is seconds long and
                    // throttling it would just mean fewer chances to improve.
                    0L,
                    0f,
                    listener,
                    Looper.getMainLooper(),
                )
                listening = true
                true
            }.getOrElse {
                Log.w(this@LocationCapture.log, "Could not listen on $provider", it)
                false
            }
        }
        if (started == 0 && continuation.isActive) {
            continuation.resume(Unit)
        }
    }

    /**
     * The newest cached fix, if it is recent enough to be about here.
     *
     * Discarded rather than improved upon when stale: an old fix that never gets replaced
     * would otherwise be stored as this assessment's location.
     */
    @SuppressLint("MissingPermission")
    private fun freshCachedFix(manager: LocationManager): Location? = runCatching {
        manager.getProviders(true)
            .mapNotNull { manager.getLastKnownLocation(it) }
            .filter { System.currentTimeMillis() - it.time <= MAX_SEED_AGE_MILLIS }
            .maxByOrNull { it.time }
    }.getOrNull()

    /**
     * More accurate wins; a fix with no accuracy at all loses to one that has it.
     *
     * Deliberately not "newer wins". Everything arriving inside the window is current, so
     * the only question left is which is more precise.
     */
    private fun isBetter(candidate: Location, current: Location?): Boolean {
        if (current == null) return true
        if (!candidate.hasAccuracy()) return false
        if (!current.hasAccuracy()) return true
        return candidate.accuracy < current.accuracy
    }

    private fun manager(): LocationManager? =
        appContext.getSystemService(LocationManager::class.java)

    /**
     * Coarse counts. A fix good to a city block still says which trading centre, which is
     * the question this data is for, and a customer who granted only coarse permission
     * has answered about as clearly as a customer can.
     */
    private fun hasPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            appContext, Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun Location.toDomain() = LocationFix(
        latitude = latitude,
        longitude = longitude,
        accuracyMetres = if (hasAccuracy()) accuracy.toDouble() else Double.MAX_VALUE,
        capturedAt = time,
    )

    private companion object {
        /**
         * How long to keep listening.
         *
         * Sized against what the customer is doing, not against how long a fix takes: the
         * intake is several questions and the photographs follow it, so ninety seconds
         * ends long before anybody is waiting on the answer. Indoors GPS often never
         * arrives at all, which is what the seed and the null return are for.
         */
        const val WINDOW_MILLIS = 90_000L

        /** Stop early at this accuracy: enough to place a shop front. */
        const val GOOD_ENOUGH_METRES = 25f

        /** Same rule as the registration screen, for the same reason. */
        const val MAX_SEED_AGE_MILLIS = 2 * 60 * 1000L
    }
}
