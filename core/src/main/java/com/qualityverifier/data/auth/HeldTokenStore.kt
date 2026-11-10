package com.qualityverifier.data.auth

/**
 * One account's tokens, kept in memory after sign-out has cleared the stored copy.
 *
 * Exists for the push at sign-out (see `AppContainer.signOut`). Signing out clears the
 * stored tokens at once, as it always did, so the phone is signed out the moment the person
 * taps the button. The push sends the account's unsent work afterwards, and it has to send
 * it as that account: if it read the stored tokens, a different account signing in during
 * the push would have the previous one's roster and names sent under its own.
 *
 * Never written to disk. A process killed mid-push loses the copy, and the work stays in
 * Room exactly as it would have without the push.
 */
class HeldTokenStore(
    accessToken: String?,
    accessTokenExpiresAt: Long,
    refreshToken: String?,
    userId: String?,
    private val now: () -> Long = System::currentTimeMillis,
) : TokenStore {

    // Volatile because the push's requests and its token provider run on different IO
    // threads; the provider's own lock and mutex serialise the writes.
    @Volatile private var access: String? = accessToken
    @Volatile private var expiresAt: Long = accessTokenExpiresAt
    @Volatile private var refresh: String? = refreshToken
    @Volatile private var user: String? = userId

    override fun accessToken() = access
    override fun refreshToken() = refresh
    override fun userId() = user
    override fun accessTokenExpiresAt() = expiresAt

    override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) {
        access = accessToken
        refresh = refreshToken
        user = userId
        // An absolute instant, as the stored copy keeps it.
        expiresAt = now() + expiresInSeconds * 1000
    }

    // The push never reads the evaluator flag; it is the pull's to refresh.
    override fun isTester() = false
    override fun setTester(value: Boolean) = Unit

    override fun clear() {
        access = null
        refresh = null
        user = null
        expiresAt = 0
    }
}
