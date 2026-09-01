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
    fun clear()

    fun isSignedIn(): Boolean = refreshToken() != null
}
