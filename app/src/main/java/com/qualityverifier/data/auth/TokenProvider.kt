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

    fun signOut() = store.clear()

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
        val refreshToken = store.refreshToken() ?: return null
        return when (val outcome = refresher(refreshToken)) {
            is RefreshOutcome.Renewed -> {
                store.save(
                    accessToken = outcome.accessToken,
                    expiresInSeconds = outcome.expiresInSeconds,
                    refreshToken = outcome.refreshToken,
                    userId = outcome.userId,
                )
                outcome.accessToken
            }

            RefreshOutcome.Rejected -> {
                // Either the token really is dead or the chain was revoked. Both mean the
                // same thing from here: sign in again.
                Log.w(TAG, "Refresh token refused; clearing credentials")
                store.clear()
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
