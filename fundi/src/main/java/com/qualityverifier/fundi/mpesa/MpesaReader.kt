package com.qualityverifier.fundi.mpesa

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.util.Log
import com.qualityverifier.data.mpesa.MpesaRepository
import com.qualityverifier.mpesa.MpesaIntake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * How Fundi Bora reads M-Pesa messages: the live receiver ([MpesaSmsReceiver]) for messages
 * arriving while the app is installed, and [scan] at app start for any that arrived while it
 * was not running. Both apply [MpesaIntake.read], which reads only M-Pesa messages received
 * after the fundi agreed.
 *
 * Adapted from Drew Burd's InboxScanner and SmsBroadcastReceiver (money-tracking-kenya,
 * commit 5355a31, MIT licence), rewritten for this app: no remote rules, no recording
 * service, no heartbeat, no look-back window, and the scan starts at the agreement.
 */
object MpesaReader {

    /** Both, or the reader stays off: a receiver without the scan would miss every message sent while the phone was off. */
    val PERMISSIONS = arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS)

    fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Enables the receiver while reading is on and disables it otherwise, so with reading
     * off the system does not even deliver a message to this app. The receiver checks the
     * switch again itself, for the moment between the switch and this call.
     */
    fun applySwitch(context: Context, reading: Boolean) {
        val state = if (reading) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context, MpesaSmsReceiver::class.java),
            state,
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * Reads the M-Pesa messages received since the agreement, or since the last scan if
     * later, that the phone has not stored. A message already stored by the receiver is
     * stored once (by its code); a message the parser cannot read is counted here only,
     * once, because the start moves past it after each message. Turning reading off stops
     * it storing at the next message (MpesaRepository.take). Returns how many were new.
     */
    suspend fun scan(context: Context, mpesa: MpesaRepository): Int = withContext(Dispatchers.IO) {
        val since = mpesa.scanFrom ?: return@withContext 0
        val readingSince = mpesa.switches.value.readingSince ?: return@withContext 0
        if (!hasPermission(context)) return@withContext 0

        val cursor = try {
            // Received messages only (sent, drafts and the outbox are the fundi's own), and
            // only from a sender whose id contains PESA, so no other conversation's text is
            // even loaded. MpesaIntake then checks the sender exactly.
            context.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT),
                "${Telephony.Sms.DATE} > ? AND ${Telephony.Sms.ADDRESS} LIKE ? " +
                    "AND (${Telephony.Sms.TYPE} IS NULL OR ${Telephony.Sms.TYPE} NOT IN (2, 3, 4, 5, 6))",
                arrayOf(since.toString(), "%PESA%"),
                "${Telephony.Sms.DATE} ASC",
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the SMS store: ${e.javaClass.simpleName}")
            null
        } ?: return@withContext 0

        var newest = since
        var stored = 0
        cursor.use { c ->
            val address = c.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val body = c.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val date = c.getColumnIndexOrThrow(Telephony.Sms.DATE)
            val dateSent = c.getColumnIndex(Telephony.Sms.DATE_SENT)
            while (c.moveToNext()) {
                val received = c.getLong(date)
                val sent = if (dateSent >= 0 && !c.isNull(dateSent)) c.getLong(dateSent) else 0L
                val reading = MpesaIntake.read(
                    sender = c.getString(address),
                    body = c.getString(body),
                    receivedAt = received,
                    // The network's time when the store has it, as Drew Burd's scanner does.
                    occurredAt = if (sent > 0) sent else received,
                    agreedAt = readingSince,
                )
                if (mpesa.take(reading)) stored++
                // After each message, not once at the end: a scan cut short (the process
                // killed, an error) must not count an unread message again next time.
                if (received > newest) {
                    newest = received
                    mpesa.scannedUpTo(newest)
                }
            }
        }
        stored
    }

    private const val TAG = "MpesaReader"
}
