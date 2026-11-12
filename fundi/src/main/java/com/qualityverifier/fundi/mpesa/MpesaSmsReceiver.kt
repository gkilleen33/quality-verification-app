package com.qualityverifier.fundi.mpesa

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.qualityverifier.fundi.FundiBoraApp
import com.qualityverifier.mpesa.MobileMoneyParser
import com.qualityverifier.mpesa.MpesaIntake
import com.qualityverifier.mpesa.MpesaReading
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Stores an M-Pesa transaction the moment its message arrives, so the Finances tab is
 * current without the fundi opening the app. Disabled in the manifest and enabled only while
 * reading is on (MpesaReader.applySwitch); it checks the switch again here as well.
 *
 * A message from any other sender is dropped before its text is joined up or read. A
 * message the parser cannot read is left to the inbox scan, which counts each one once.
 */
class MpesaSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as? FundiBoraApp ?: return
        val mpesa = app.container.mpesa
        val readingSince = mpesa.switches.value.readingSince ?: return

        val parts = try {
            Telephony.Sms.Intents.getMessagesFromIntent(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Could not read the SMS parts: ${e.javaClass.simpleName}")
            return
        }
        if (parts.isNullOrEmpty()) return
        val receivedAt = System.currentTimeMillis()

        // A long message arrives in parts; join each M-Pesa sender's parts, in order.
        val bySender = linkedMapOf<String, Pair<StringBuilder, Long>>()
        for (part in parts) {
            val sender = part.displayOriginatingAddress ?: continue
            if (MobileMoneyParser.networkForSender(sender) != MobileMoneyParser.NETWORK_MPESA) continue
            val (text, at) = bySender.getOrPut(sender) { StringBuilder() to 0L }
            text.append(part.displayMessageBody.orEmpty())
            bySender[sender] = text to maxOf(at, part.timestampMillis)
        }
        if (bySender.isEmpty()) return

        // Off the broadcast thread, as Drew Burd's receiver does, so a slow database never
        // gets the receiver killed. Bounded well inside the ten seconds a receiver is given.
        val pending = goAsync()
        app.background.launch {
            try {
                var stored = 0
                withTimeoutOrNull(RECEIVER_BUDGET_MILLIS) {
                    for ((sender, value) in bySender) {
                        val (text, sentAt) = value
                        val reading = MpesaIntake.read(
                            sender = sender,
                            body = text.toString(),
                            receivedAt = receivedAt,
                            occurredAt = if (sentAt > 0) sentAt else receivedAt,
                            agreedAt = readingSince,
                        )
                        if (reading is MpesaReading.Read && mpesa.take(reading)) stored++
                    }
                }
                // Sent in the background, after the receiver has finished; anything not sent
                // stays for the next sync.
                if (stored > 0) app.background.launch { runCatching { mpesa.push() } }
            } catch (e: Exception) {
                // A crash here would cost the fundi nothing but this one message, which the
                // scan at the next start reads again.
                Log.w(TAG, "Could not store an M-Pesa message: ${e.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MpesaSmsReceiver"
        const val RECEIVER_BUDGET_MILLIS = 8_000L
    }
}
