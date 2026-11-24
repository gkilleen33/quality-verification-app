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

    /**
     * The server's last answer about this account's terms: true when it has terms to agree
     * to, false when it has agreed to the current ones, null when the phone has not asked.
     *
     * Cached for the same reason as [isTester]: Fundi Bora chooses its first screen from it
     * before any request can answer, and offline it keeps the last answer. Kagua never
     * reads it. Defaulted so a store that never holds it (the push's copy, test fakes)
     * reads as "not asked".
     */
    fun termsRequired(): Boolean? = null

    fun setTermsRequired(value: Boolean?) = Unit

    /**
     * Whether this is a Kagua account on a shop's tablet (server V39, release scope row 12),
     * from the sign-in answer and every profile read. Cached, as [isTester] is, because the
     * app decides how to behave before any request can answer. False until the server says
     * otherwise, so an app updated from v0.6.1 keeps behaving as it did. Defaulted so a store
     * that never holds it (the push's copy, test fakes) reads as an ordinary account.
     *
     * Unlike everything else here, [clear] leaves it: it describes the handset until another
     * account signs in, so a forced sign-out does not stop a shop tablet's wipe
     * ([com.qualityverifier.data.shop.ShopTabletFlag]).
     */
    fun isShopMode(): Boolean = false

    fun setShopMode(value: Boolean) = Unit

    fun clear()

    fun isSignedIn(): Boolean = refreshToken() != null
}
