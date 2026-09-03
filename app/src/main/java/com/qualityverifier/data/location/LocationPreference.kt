package com.qualityverifier.data.location

import android.content.Context

/**
 * Whether to record where an assessment was made.
 *
 * Chosen at sign-up and changeable in Settings for as long as the app is installed. On by
 * default, and said so plainly on the form rather than buried — the point of collecting
 * it is research, and a default nobody was told about is not consent.
 *
 * Plain [android.content.SharedPreferences], not the encrypted store the tokens live in.
 * This is a preference, not a secret: knowing that somebody agreed to share locations
 * discloses nothing, and the fixes themselves are never held here.
 *
 * Device-wide rather than per account. Two accounts on one handset is not something the
 * pilot expects, and the alternative — keying this by user id — would leave the setting
 * unreadable at exactly the moments it is needed, before sign-in completes and after
 * sign-out.
 */
class LocationPreference(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Defaults to true for an installation that has never been asked, which is what makes
     * the sign-up toggle read correctly the first time it is drawn.
     */
    var recordAtStart: Boolean
        get() = prefs.getBoolean(KEY_RECORD_AT_START, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RECORD_AT_START, value).apply()
        }

    private companion object {
        const val PREFS_NAME = "kagua_preferences"
        const val KEY_RECORD_AT_START = "record_location_at_start"
    }
}
