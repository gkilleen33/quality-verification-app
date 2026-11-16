package com.qualityverifier.tracker

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** PesaLog's process: the container, and work that outlives every screen. */
class TrackerApp : Application() {
    lateinit var container: TrackerContainer
        private set

    /** The listener's stores and pushes, and the push at start. */
    val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        container = TrackerContainer(
            context = this,
            baseUrl = BuildConfig.SERVER_BASE_URL,
            versionCode = BuildConfig.VERSION_CODE,
            checkForUpdates = !BuildConfig.DEBUG,
        )
        container.appVersionCheck.check()
        // Whatever the phone owes the server, if sending is on.
        background.launch {
            try {
                if (container.isSignedIn) container.repository.push()
            } catch (e: Exception) {
                Log.w(TAG, "Push at start failed; the next one tries again", e)
            }
        }
    }

    private companion object {
        const val TAG = "TrackerApp"
    }
}

/** The single dependency lookup every screen uses, as Fundi Bora's fundiContainer is. */
@Composable
fun trackerContainer(): TrackerContainer = LocalContext.current.trackerContainer()

fun Context.trackerContainer(): TrackerContainer = (applicationContext as TrackerApp).container

/** The app's display name, from build.gradle.kts, the one place it is set. */
const val APP_NAME: String = BuildConfig.DISPLAY_NAME
