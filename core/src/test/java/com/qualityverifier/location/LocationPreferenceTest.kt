package com.qualityverifier.location

import android.content.SharedPreferences
import com.qualityverifier.data.auth.LocalAccountGuard
import com.qualityverifier.data.auth.LocalAccountOwner
import com.qualityverifier.data.location.LocationPreference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The location answer when a different maker signs in on the same phone.
 *
 * The answer is stored per installation. Without the forget at the account switch, maker
 * A's yes would carry over to maker B, and every assessment B made would record a point
 * B was never asked about.
 *
 * The `if (locationAnswerRequired)` that keeps Kagua out of this lives in `AppContainer`,
 * which needs Room and a real Context and has no JVM test. The clear below calls
 * [LocationPreference.forget] the way that branch does in Fundi Bora.
 */
class LocationPreferenceTest {

    @Test
    fun `a different maker signing in is asked again and records nothing until then`() {
        val prefs = FakePrefs()
        val preference = LocationPreference(prefs, answerRequired = true)
        val guard = LocalAccountGuard(FakeOwner(recorded = MAKER_A)) { preference.forget() }
        preference.answer(true)
        assertTrue(preference.records)

        assertTrue(guard.beforeSigningIn(MAKER_B))

        assertTrue(preference.needsAnswer)
        assertFalse(preference.records)
        assertTrue(prefs.values.isEmpty())
    }

    @Test
    fun `the same maker signing back in keeps the answer`() {
        val preference = LocationPreference(FakePrefs(), answerRequired = true)
        val guard = LocalAccountGuard(FakeOwner(recorded = MAKER_A)) { preference.forget() }
        preference.answer(true)

        assertFalse(guard.beforeSigningIn(MAKER_A))

        assertFalse(preference.needsAnswer)
        assertTrue(preference.records)
    }

    @Test
    fun `a no is forgotten too, and the next maker is still asked`() {
        val preference = LocationPreference(FakePrefs(), answerRequired = true)
        preference.answer(false)

        preference.forget()

        assertTrue(preference.needsAnswer)
        assertFalse(preference.records)
    }

    // Kagua is not forgotten (AppContainer skips it), so its stored "no" stays a no.
    @Test
    fun `Kagua's switch is untouched by a sign-in that does not forget`() {
        val preference = LocationPreference(FakePrefs(), answerRequired = false)
        val guard = LocalAccountGuard(FakeOwner(recorded = MAKER_A)) {}
        preference.recordAtStart = false

        assertTrue(guard.beforeSigningIn(MAKER_B))

        assertFalse(preference.recordAtStart)
        assertFalse(preference.records)
        assertFalse(preference.needsAnswer)
    }

    // ---------------------------------------------------------------- harness

    private class FakeOwner(private var recorded: String?) : LocalAccountOwner {
        override fun userId() = recorded
        override fun record(userId: String) {
            recorded = userId
        }
    }

    /** Booleans are all [LocationPreference] stores; the rest is there to satisfy the interface. */
    private class FakePrefs : SharedPreferences {
        val values = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?) = values[key] as String? ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) =
            values[key] as MutableSet<String>? ?: defValues
        override fun getInt(key: String?, defValue: Int) = values[key] as Int? ?: defValue
        override fun getLong(key: String?, defValue: Long) = values[key] as Long? ?: defValue
        override fun getFloat(key: String?, defValue: Float) = values[key] as Float? ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean) = values[key] as Boolean? ?: defValue
        override fun contains(key: String?) = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(
            listener: SharedPreferences.OnSharedPreferenceChangeListener?,
        ) = Unit

        /** Applies on commit() or apply(), removals first, as Android's editor does. */
        private inner class Editor : SharedPreferences.Editor {
            private val puts = mutableMapOf<String, Any?>()
            private val removals = mutableSetOf<String>()
            private var clearAll = false

            private fun put(key: String?, value: Any?): SharedPreferences.Editor {
                puts[key!!] = value
                return this
            }

            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values)
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)

            override fun remove(key: String?): SharedPreferences.Editor {
                removals += key!!
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                if (clearAll) values.clear()
                removals.forEach { values.remove(it) }
                values.putAll(puts)
                return true
            }

            override fun apply() {
                commit()
            }
        }
    }

    private companion object {
        const val MAKER_A = "11111111-2222-3333-4444-555555555555"
        const val MAKER_B = "99999999-8888-7777-6666-555555555555"
    }
}
