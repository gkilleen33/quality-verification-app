package com.qualityverifier.data.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences

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
 * sign-out. In Fundi Bora the answer is still one person's, so when a different account
 * signs in it is forgotten (see [forget]) and the new maker is asked again.
 *
 * ## Answered, and the switch
 *
 * Fundi Bora's switch shipped defaulting to on before any maker had been asked about a
 * point per assessment; its setup asked only about the shop. So on that app the switch
 * counts only once [answered] is true, and [answered] defaults to false. An install from
 * before the question existed therefore records nothing until the maker answers it once,
 * and nothing stored needs rewriting for that to hold. Kagua passes [answerRequired]
 * false: its sign-up form shows the switch, and every Kagua account has passed it.
 *
 * @param answerRequired true in Fundi Bora. See above.
 */
class LocationPreference internal constructor(
    private val prefs: SharedPreferences,
    private val answerRequired: Boolean,
) {

    // The app's constructor. The one taking the prefs directly lets plain JVM tests run
    // this class without a Context.
    constructor(context: Context, answerRequired: Boolean = false) : this(
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        answerRequired,
    )

    /**
     * The switch as stored. Defaults to true for an installation that has never been
     * asked, which is what makes Kagua's sign-up toggle read correctly the first time it
     * is drawn. Read [records] to decide anything; this alone ignores [answered].
     */
    var recordAtStart: Boolean
        get() = prefs.getBoolean(KEY_RECORD_AT_START, true)
        set(value) {
            prefs.edit().putBoolean(KEY_RECORD_AT_START, value).apply()
        }

    /**
     * Whether the person has answered the question themselves: on a screen that asked it,
     * or by moving the switch in Settings. False until then.
     */
    val answered: Boolean
        get() = prefs.getBoolean(KEY_ANSWERED, false)

    /** Whether assessments record their point. What capture reads and the switch shows. */
    val records: Boolean
        get() = LocationRule.records(recordAtStart, answered, answerRequired)

    /** True while this app still has to ask. Only ever true in Fundi Bora. */
    val needsAnswer: Boolean
        get() = answerRequired && !answered

    /** The person's answer, yes or no. Every screen that asks writes through this. */
    fun answer(record: Boolean) {
        prefs.edit()
            .putBoolean(KEY_RECORD_AT_START, record)
            .putBoolean(KEY_ANSWERED, true)
            .apply()
    }

    /**
     * Forgets the answer, as if this installation had never been asked. With [answerRequired]
     * that makes [needsAnswer] true and [records] false until somebody answers again.
     *
     * For a different account signing in on Fundi Bora (see `AppContainer`): the answer was
     * the previous maker's, and without this every assessment of the next one would record a
     * point they were never asked about.
     *
     * commit(), not apply(), for the reason
     * [com.qualityverifier.data.auth.PrefsLocalAccountOwner.record] gives: the new
     * owner is recorded straight after this, and a process killed in between must not leave
     * the new account holding the previous one's yes.
     */
    @SuppressLint("ApplySharedPref")
    fun forget() {
        prefs.edit()
            .remove(KEY_RECORD_AT_START)
            .remove(KEY_ANSWERED)
            .commit()
    }

    private companion object {
        const val PREFS_NAME = "kagua_preferences"
        const val KEY_RECORD_AT_START = "record_location_at_start"
        const val KEY_ANSWERED = "record_location_answered"
    }
}
