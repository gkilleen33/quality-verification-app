package com.qualityverifier.tracker.listen

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/**
 * Notification access: whether it is on, and where in Android's settings to turn it on.
 *
 * Android keeps this permission in its settings rather than asking in a dialog. On Android 13
 * and later, an app installed from outside an app store also has it blocked as a "restricted
 * setting" until the person allows restricted settings on the app's own App info page (its
 * menu, then "Allow restricted settings"). PesaLog is installed from a release page or by a
 * field officer's laptop, so the guided step covers both.
 */
object ListenerAccess {

    fun component(context: Context) = ComponentName(context, MoneyNotificationListener::class.java)

    /** Whether the person has turned on notification access for PesaLog. */
    fun isEnabled(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)

    /** True on the Android versions that may block notification access for a sideloaded app. */
    val restrictedSettingsPossible: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * The notification access page: PesaLog's own switch where Android has one (11 and later),
     * the list of apps otherwise. Either may be missing on a phone whose maker removed it, so
     * the caller falls back to [listSettings] and then [appDetails].
     */
    fun accessSettings(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
        } else {
            listSettings()
        }

    fun listSettings(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    /** PesaLog's App info page, where "Allow restricted settings" is, in the menu. */
    fun appDetails(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** Opens the first of [intents] the phone can show. False when it can show none. */
    fun open(context: Context, vararg intents: Intent): Boolean {
        for (intent in intents) {
            val started = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (started) return true
        }
        return false
    }
}
