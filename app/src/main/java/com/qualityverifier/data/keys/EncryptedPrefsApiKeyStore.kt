@file:Suppress("DEPRECATION")

package com.qualityverifier.data.keys

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores the API key in [EncryptedSharedPreferences], encrypted with an
 * Android-Keystore-backed master key. The plaintext key never touches disk.
 *
 * Note: `androidx.security:security-crypto` is deprecated by Jetpack but remains
 * functional and Keystore-backed. It sits behind [ApiKeyStore] precisely so it can be
 * swapped for a hand-rolled Keystore AES-GCM implementation without touching callers.
 *
 * The deprecation is suppressed here and nowhere else, so the day it needs replacing,
 * this file is the only one that changes.
 */
class EncryptedPrefsApiKeyStore(private val context: Context) : ApiKeyStore {

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        cached?.let { return it }
        return synchronized(this) {
            cached ?: try {
                create().also { cached = it }
            } catch (e: Exception) {
                // A device migration, restored backup, or cleared Keystore leaves an
                // undecryptable prefs file. Wipe it and try once more rather than
                // crash-looping the user out of the app forever.
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
        // commit(), not apply(): the clear must be flushed to disk before the file is
        // deleted, otherwise a pending async write can resurrect it.
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteSharedPreferences(PREFS_NAME)
    }.isSuccess

    override fun hasKey(): Boolean = !get().isNullOrBlank()

    override fun get(): String? = prefs()?.getString(KEY_API_KEY, null)?.takeIf { it.isNotBlank() }

    override fun set(key: String) {
        prefs()?.edit()?.putString(KEY_API_KEY, key.trim())?.apply()
    }

    override fun clear() {
        prefs()?.edit()?.remove(KEY_API_KEY)?.apply()
    }

    private companion object {
        const val TAG = "ApiKeyStore"
        const val PREFS_NAME = "qv_secure_prefs"
        const val MASTER_KEY_ALIAS = "qv_master_key"
        const val KEY_API_KEY = "anthropic_api_key"
    }
}
