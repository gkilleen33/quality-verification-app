package com.qualityverifier.fundi

import android.app.Application
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AppId
import com.qualityverifier.fundi.reports.ReportFiles
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

        // Any report left from the last session goes now, so no PDF outlives one app
        // session on the phone (plan v7 §5.4). Also cleared before each new report.
        background.launch { ReportFiles.clear(this@FundiBoraApp) }
    }

    /** Work that outlives every screen: clearing the last session's reports at start. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
}
