package com.qualityverifier.data.auth

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What a refresh attempt came back with. */
sealed interface RefreshOutcome {
    data class Renewed(
        val accessToken: String,
        val expiresInSeconds: Long,
        val refreshToken: String,
        val userId: String,
    ) : RefreshOutcome

    /** The server refused the token. Nothing to do but sign in again. */
    data object Rejected : RefreshOutcome

    /** Offline, or the server is down. What we hold may still work later. */
    data object Unavailable : RefreshOutcome
}

/**
 * Hands out an access token, refreshing at most one at a time.
 *
 * The single-flight guarantee is not an optimisation. The server rotates refresh tokens
 * and treats a spent one coming back as theft, revoking every token for that user — so
 * two requests that both hit a 401 and both refresh with the same token would sign the
 * customer out completely. A phone reopened after a while fires several requests at once,
 * which is exactly the shape that triggers it.
 *
 * The mechanism is one mutex plus a double check. Each caller tests whether it still
 * needs a refresh *after* acquiring the lock, because by then the token it was unhappy
 * with has usually already been replaced by whoever held the lock first — and refreshing
 * again would be the second use of a token the server has just retired.
 */
class TokenProvider(
    private val store: TokenStore,
    private val refresher: suspend (refreshToken: String) -> RefreshOutcome,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()

    /**
     * Guards [generation] and every write to the store that depends on it.
     *
     * Separate from [mutex] because [signOut] is called from a click handler and cannot
     * suspend, and because it must not wait behind a refresh that may take 30 seconds on a
     * bad connection. Held only around a read and a store write, never across a request.
     */
    private val lock = Any()

    /**
     * Counts sign-outs. A refresh notes it before sending and saves its answer only if it
     * has not moved, so a refresh still in flight when somebody signs out cannot write
     * their tokens back afterwards, or clear the next account's on a late refusal.
     */
    private var generation = 0L

    /**
     * A usable access token, refreshed early if it is close to expiry.
     *
     * Proactive rather than waiting for a 401: a nine-photo turn is megabytes on a mobile
     * connection, and finding out the token died after uploading all of it wastes the
     * upload as well as the round trip.
     */
    suspend fun accessToken(): String? {
        usableToken()?.let { return it }
        return mutex.withLock {
            // Someone may have refreshed while this call waited for the lock.
            usableToken() ?: refresh()
        }
    }

    /**
     * Called when the server has actually refused a token.
     *
     * [refusedToken] is the one it refused. If what is stored no longer matches it,
     * another caller has already refreshed and this one should just use the new token;
     * refreshing again would spend a retired one and get the customer signed out.
     */
    suspend fun refreshAfterUnauthorized(refusedToken: String?): String? {
        replacementFor(refusedToken)?.let { return it }
        return mutex.withLock {
            replacementFor(refusedToken) ?: refresh()
        }
    }

    fun signOut() = synchronized(lock) {
        generation++
        store.clear()
    }

    /**
     * Signs out as [signOut] does, and hands back a copy of the tokens it cleared, for one
     * last push of the account's unsent work. Null when nothing was signed in.
     *
     * Copied and cleared under the one [lock], so no refresh can save between the two.
     */
    fun signOutKeepingCopy(): HeldTokenStore? = synchronized(lock) {
        val copy = store.refreshToken()?.let { refreshToken ->
            HeldTokenStore(
                accessToken = store.accessToken(),
                accessTokenExpiresAt = store.accessTokenExpiresAt(),
                // A refresh holding the mutex is about to spend this token, and its answer
                // will be discarded because the generation moves below. Presenting the
                // spent token again is what the server reads as theft, revoking the
                // account on every phone. So the copy goes without it, and the push makes
                // do with the access token, or sends nothing if that is near expiry.
                refreshToken = refreshToken.takeUnless { mutex.isLocked },
                userId = store.userId(),
                now = now,
            )
        }
        generation++
        store.clear()
        copy
    }

    /**
     * Which sign-in the stored tokens belong to. A caller whose answer carries new tokens
     * reads it before sending and passes it to [adopt].
     */
    fun generation(): Long = synchronized(lock) { generation }

    /**
     * Saves a token pair the server handed back outside a refresh: the one a password
     * change returns. Under [mutex], so it cannot interleave with a refresh still holding
     * the old token; and only if nobody signed out since [since], so a slow answer cannot
     * sign the phone back in. Returns whether it was saved.
     */
    suspend fun adopt(
        since: Long,
        accessToken: String,
        expiresInSeconds: Long,
        refreshToken: String,
        userId: String,
    ): Boolean = mutex.withLock {
        saveIfStill(since, accessToken, expiresInSeconds, refreshToken, userId)
    }

    private fun saveIfStill(
        since: Long,
        accessToken: String,
        expiresInSeconds: Long,
        refreshToken: String,
        userId: String,
    ): Boolean = synchronized(lock) {
        if (generation != since) return@synchronized false
        store.save(
            accessToken = accessToken,
            expiresInSeconds = expiresInSeconds,
            refreshToken = refreshToken,
            userId = userId,
        )
        true
    }

    /** The stored token, if it exists and is not about to expire. */
    private fun usableToken(): String? =
        store.accessToken()?.takeIf { store.accessTokenExpiresAt() - now() >= REFRESH_SKEW_MILLIS }

    /** A token that is already different from the refused one, meaning somebody fixed it. */
    private fun replacementFor(refusedToken: String?): String? {
        if (refusedToken == null) return null
        val current = store.accessToken() ?: return null
        return current.takeIf { it != refusedToken }
    }

    /** Only ever called while holding [mutex]. */
    private suspend fun refresh(): String? {
        // Read before the token, so a sign-out between the two shows up either as no
        // token or as a changed generation.
        val since = generation()
        val refreshToken = store.refreshToken() ?: return null
        return when (val outcome = refresher(refreshToken)) {
            is RefreshOutcome.Renewed -> {
                val saved = saveIfStill(
                    since,
                    accessToken = outcome.accessToken,
                    expiresInSeconds = outcome.expiresInSeconds,
                    refreshToken = outcome.refreshToken,
                    userId = outcome.userId,
                )
                if (saved) {
                    outcome.accessToken
                } else {
                    // Signed out while this was in flight. Saving would sign the phone
                    // back in to the account just left.
                    Log.i(TAG, "Signed out during a refresh; discarding its tokens")
                    null
                }
            }

            RefreshOutcome.Rejected -> {
                // Either the token really is dead or the chain was revoked. Both mean the
                // same thing from here: sign in again. Unless somebody already signed out
                // meanwhile, in which case what the store holds now, if anything, belongs
                // to the next sign-in and is not this refusal's to clear.
                Log.w(TAG, "Refresh token refused; clearing credentials")
                synchronized(lock) { if (generation == since) store.clear() }
                null
            }

            RefreshOutcome.Unavailable -> {
                // Offline. Keep everything: the access token may still be inside its
                // lifetime, and discarding the refresh token would sign somebody out for
                // losing signal on a bus.
                Log.i(TAG, "Refresh unavailable; keeping existing credentials")
                null
            }
        }
    }

    private companion object {
        const val TAG = "TokenProvider"

        /**
         * Two minutes. Long enough that a slow photo upload starting now still has a valid
         * token when it finishes, short enough not to refresh on every request.
         */
        const val REFRESH_SKEW_MILLIS = 2 * 60 * 1000L
    }
}
