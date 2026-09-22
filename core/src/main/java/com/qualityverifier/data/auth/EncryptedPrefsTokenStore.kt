@file:Suppress("DEPRECATION")

package com.qualityverifier.data.auth

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the tokens in [EncryptedSharedPreferences], Keystore-backed, exactly as the API
 * key was stored before it.
 *
 * The self-healing behaviour is carried over deliberately: a device migration, restored
 * backup or cleared Keystore leaves an undecryptable prefs file, and the alternative to
 * wiping it is crash-looping somebody out of the app forever. Losing the tokens costs a
 * sign-in; losing the app costs the user.
 *
 * `androidx.security:security-crypto` is deprecated by Jetpack but still functional. The
 * suppression is at the top of this file and nowhere else, so the day it needs replacing,
 * this is the only file that changes.
 */
class EncryptedPrefsTokenStore(private val context: Context) : TokenStore {

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: try {
                create().also { cached = it }
            } catch (e: Exception) {
                Log.w(TAG, "Encrypted prefs unreadable, recreating", e)
                if (deleteCorruptPrefs()) {
                    runCatching { create() }.getOrNull()?.also { cached = it }
                } else {
                    null
                }
            }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    @SuppressLint("ApplySharedPref")
    private fun deleteCorruptPrefs(): Boolean = runCatching {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(PREFS_NAME)
    }.isSuccess

    override fun accessToken(): String? = read(KEY_ACCESS)

    override fun refreshToken(): String? = read(KEY_REFRESH)

    override fun userId(): String? = read(KEY_USER)

    override fun accessTokenExpiresAt(): Long = prefs()?.getLong(KEY_EXPIRES_AT, 0L) ?: 0L

    @SuppressLint("ApplySharedPref")
    override fun save(
        accessToken: String,
        expiresInSeconds: Long,
        refreshToken: String,
        userId: String,
    ) {
        // commit(), not apply(). This is written under the refresh mutex and the very next
        // request reads it back; an async write can still be in flight at that point, and
        // a caller that reads the old token would present a spent one — which the server
        // reads as theft and answers by revoking the chain.
        prefs()?.edit()
            ?.putString(KEY_ACCESS, accessToken)
            ?.putString(KEY_REFRESH, refreshToken)
            ?.putString(KEY_USER, userId)
            // Stored as an absolute instant rather than a duration, so it survives the
            // process being killed between refresh and use.
            ?.putLong(KEY_EXPIRES_AT, System.currentTimeMillis() + expiresInSeconds * 1000)
            ?.commit()
    }

    override fun isTester(): Boolean = prefs()?.getBoolean(KEY_TESTER, false) ?: false

    @SuppressLint("ApplySharedPref")
    override fun setTester(value: Boolean) {
        prefs()?.edit()?.putBoolean(KEY_TESTER, value)?.commit()
    }

    @SuppressLint("ApplySharedPref")
    override fun clear() {
        prefs()?.edit()
            ?.remove(KEY_ACCESS)
            ?.remove(KEY_REFRESH)
            ?.remove(KEY_USER)
            ?.remove(KEY_EXPIRES_AT)
            ?.remove(KEY_TESTER)
            ?.commit()
    }

    private fun read(key: String): String? =
        prefs()?.getString(key, null)?.takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "TokenStore"

        /**
         * A separate file from the old key store, not a new field inside it. The API key
         * file is deleted outright on upgrade — see AppContainer — and mixing the two
         * would mean deleting the tokens with it.
         */
        const val PREFS_NAME = "kagua_tokens"
        const val MASTER_KEY_ALIAS = "qv_master_key"
        const val KEY_ACCESS = "access_token"
        const val KEY_REFRESH = "refresh_token"
        const val KEY_USER = "user_id"
        const val KEY_EXPIRES_AT = "access_expires_at"
        const val KEY_TESTER = "is_tester"
    }
}
