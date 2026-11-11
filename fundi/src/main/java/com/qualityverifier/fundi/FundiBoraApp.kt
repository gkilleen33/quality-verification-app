package com.qualityverifier.fundi

import android.app.Application
import android.util.Log
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AppId
import com.qualityverifier.fundi.mpesa.MpesaReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Fundi Bora's process.
 *
 * The container is `:core`'s, unchanged — both apps sign in against the same server,
 * store assessments in the same shape and upload photographs the same way, and the parts
 * of that which are easy to get subtly wrong are worth having exactly one copy of. What
 * differs is only the base URL each app compiles in, which is why that is a parameter.
 *
 * When this app needs wiring Kagua does not — a workshop-profile repository, shortly —
 * it composes the container rather than replacing it.
 */
class FundiBoraApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(
            context = this,
            baseUrl = BuildConfig.SERVER_BASE_URL,
            // Fundi Bora's own endpoint, which is what selects the coaching prompt.
            // Kagua's app does not know this path exists.
            chatPath = "v1/fundi/chat",
            // Both apps share version numbers, so the server needs the id to know whose
            // latest and minimum apply.
            appId = AppId.FUNDI,
            versionCode = BuildConfig.VERSION_CODE,
            // A debug build sends its version but is not prompted to update.
            checkForUpdates = !BuildConfig.DEBUG,
            // The switch shipped on before makers were asked about a point per
            // assessment, so here it counts only once they have answered.
            locationAnswerRequired = true,
        )
        // At start; a second check follows each sign-in. See AppContainer.
        container.appVersionCheck.check()

        // The M-Pesa receiver follows the reading switch for as long as the process lives,
        // so turning reading off in Settings disables it at once.
        background.launch {
            container.mpesa.switches.collect { MpesaReader.applySwitch(this@FundiBoraApp, it.reading) }
        }
        // At start, the M-Pesa messages that arrived while the app was not running: only
        // those received after the agreement and not yet stored (MpesaReader.scan). Then
        // whatever the phone owes the server, if sending is on.
        background.launch {
            try {
                if (container.mpesa.switches.value.reading) MpesaReader.scan(this@FundiBoraApp, container.mpesa)
                if (container.isSignedIn) container.mpesa.push()
            } catch (e: Exception) {
                Log.w(TAG, "M-Pesa scan at start failed; the next start tries again", e)
            }
        }
    }

    /** Work that outlives every screen: the M-Pesa scan at start and the receiver's pushes. */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private companion object {
        const val TAG = "FundiBoraApp"
    }
}
