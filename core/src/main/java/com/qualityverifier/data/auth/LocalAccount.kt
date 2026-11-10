package com.qualityverifier.data.auth

import android.annotation.SuppressLint
import android.content.Context

/**
 * Which account the phone's local data belongs to.
 *
 * Kept apart from [TokenStore] because it has to outlive sign-out: the question it answers
 * is asked at the next sign-in, after the tokens are gone.
 */
interface LocalAccountOwner {
    fun userId(): String?
    fun record(userId: String)
}

/**
 * Plain [android.content.SharedPreferences], not the encrypted file the tokens live in.
 *
 * A user id is a random UUID and discloses nothing. The encrypted file also wipes itself
 * when the Keystore is lost, and a wiped record here would read as "nobody recorded",
 * which keeps the previous account's data for the next one: the one failure this exists
 * to prevent.
 */
class PrefsLocalAccountOwner(context: Context) : LocalAccountOwner {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun userId(): String? = prefs.getString(KEY_USER, null)?.takeIf { it.isNotBlank() }

    // commit(), not apply(): the tokens are saved straight after this, and a process killed
    // in between must not leave the new account signed in with the old owner recorded.
    @SuppressLint("ApplySharedPref")
    override fun record(userId: String) {
        prefs.edit().putString(KEY_USER, userId).commit()
    }

    private companion object {
        const val PREFS_NAME = "kagua_local_account"
        const val KEY_USER = "user_id"
    }
}

/**
 * Clears the phone's local data when a different account signs in.
 *
 * Signing out clears only the tokens. Room and the photos stay, so that somebody who signs
 * out and back in keeps work the server has not seen yet. Without this, the next account
 * to sign in would see the last one's pieces and workers, and its first sync would push
 * the last one's unsynced roster and names to the server under the new account.
 *
 * Called by [AuthClient] once the server has accepted a sign-in or registration and before
 * the tokens are saved. Nothing can sync for the new account until the tokens exist, and
 * no screen of the signed-in app exists until the sign-in returns, so the clear lands
 * before either can read the old data.
 */
class LocalAccountGuard(
    private val owner: LocalAccountOwner,
    /** Every Room table the app owns, and the photos. Blocking; called off the main thread. */
    private val clearLocalData: () -> Unit,
) {

    /**
     * Records [userId] as the owner, clearing first if somebody else was. Returns whether
     * it cleared.
     *
     * Nothing recorded means a first sign-in, or an install from before this existed whose
     * owner [adoptIfUnrecorded] could not learn. Either way there is no evidence the data
     * belongs to anybody else, and deleting a returning user's unsynced work on a guess
     * would lose what the server has never seen.
     *
     * If the clear throws, nothing is recorded and the exception reaches the sign-in,
     * which fails: better than signing the new account in on top of the old one's data.
     */
    fun beforeSigningIn(userId: String): Boolean {
        val recorded = owner.userId()
        val clear = recorded != null && recorded != userId
        if (clear) clearLocalData()
        owner.record(userId)
        return clear
    }

    /**
     * Records the account already signed in on an install from before this existed.
     *
     * Its tokens still name the account, so the record can be filled in at start rather
     * than waiting for a sign-in that would find nothing recorded. Reads [signedInUserId]
     * only when nothing is recorded, so after the first start it costs nothing.
     */
    fun adoptIfUnrecorded(signedInUserId: () -> String?) {
        if (owner.userId() != null) return
        signedInUserId()?.takeIf { it.isNotBlank() }?.let(owner::record)
    }
}
