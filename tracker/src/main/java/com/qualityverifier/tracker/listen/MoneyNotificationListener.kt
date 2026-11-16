package com.qualityverifier.tracker.listen

import android.content.ComponentName
import android.os.Build
import android.provider.Telephony
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.qualityverifier.tracker.TrackerApp
import com.qualityverifier.tracker.data.TakeOutcome
import kotlinx.coroutines.launch

/**
 * PesaLog's only way of reading anything: Android's notification listener, which the person
 * turns on in Android's settings.
 *
 * Android hands this every notification on the phone. All but mobile money messages are
 * dropped by [NotificationReader] before their text is read, and nothing about them is
 * stored or counted. Before even that, nothing is looked at while nobody is signed in or
 * reading is off.
 *
 * What it cannot see: messages that arrived before PesaLog was installed, and notifications
 * dismissed while it was not connected. When Android connects it (after access is turned on,
 * after a reboot, after the process was stopped), it reads the matching notifications still
 * showing, so a message that arrived meanwhile and is still on screen is caught.
 *
 * Callbacks arrive on the main thread (from Android 7); the reading here is a few bundle
 * lookups, and the storing goes to the app's background scope.
 */
class MoneyNotificationListener : NotificationListenerService() {

    private val app: TrackerApp get() = application as TrackerApp

    override fun onListenerConnected() {
        super.onListenerConnected()
        app.container.repository.listenerConnected()
        // Only safe once connected (NotificationListenerService's own rule).
        val showing = runCatching { activeNotifications?.toList() }
            .onFailure { Log.w(TAG, "Could not read the notifications still showing", it) }
            .getOrNull().orEmpty()
        handle(showing)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        app.container.repository.listenerDisconnected()
        // Ask Android to bind again; it does only while notification access is still on.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, MoneyNotificationListener::class.java))
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { handle(listOf(it)) }
    }

    private fun handle(posted: List<StatusBarNotification>) {
        if (posted.isEmpty()) return
        val container = app.container
        val repository = container.repository
        // Nothing at all while signed out or with reading off.
        if (!container.isSignedIn || !repository.switches.value.reading) return

        // The SMS app the person chose; its notifications are the only ones read.
        val smsApp = Telephony.Sms.getDefaultSmsPackage(this)
        val messages = posted.flatMap { sbn ->
            when (val outcome = NotificationReader.read(SbnSource(sbn), smsApp)) {
                is ReaderOutcome.Messages -> outcome.messages
                is ReaderOutcome.Dropped -> emptyList()
            }
        }
        if (messages.isEmpty()) return

        app.background.launch {
            try {
                var stored = false
                messages.forEach { if (repository.take(it) == TakeOutcome.STORED) stored = true }
                // Sent now when sending is on and there is signal; otherwise at the next push.
                if (stored) repository.push()
            } catch (e: Exception) {
                // The text is never logged: only that storing failed.
                Log.w(TAG, "Storing a mobile money message failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "PesaLogListener"
    }
}
