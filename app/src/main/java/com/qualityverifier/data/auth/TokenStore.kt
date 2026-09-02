package com.qualityverifier.data.auth

/**
 * The credentials the phone holds after signing in.
 *
 * Replaces [com.qualityverifier.data.keys.ApiKeyStore]. The difference that matters is
 * not the shape but the blast radius: an Anthropic key on a handset granted unmetered
 * access to our whole account, while a refresh token grants one customer's assessments
 * and can be revoked from the server the moment a phone is lost.
 */
interface TokenStore {
    fun accessToken(): String?
    fun refreshToken(): String?
    fun userId(): String?

    /** Expiry as epoch millis, so a token can be replaced before it is refused. */
    fun accessTokenExpiresAt(): Long

    fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String)

    /** Everything, on sign-out or when the server refuses the refresh token. */
    /**
     * Whether this account is one of our evaluators.
     *
     * Cached rather than asked for on demand: it decides whether a questionnaire appears
     * at the end of an assessment, and that moment is often out of signal. Refreshed on
     * every sync, so a promotion from the portal reaches the phone without a re-install.
     */
    fun isTester(): Boolean

    fun setTester(value: Boolean)

    fun clear()

    fun isSignedIn(): Boolean = refreshToken() != null
}
