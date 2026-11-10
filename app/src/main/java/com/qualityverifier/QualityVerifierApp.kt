package com.qualityverifier

import android.app.Application
import com.qualityverifier.di.AppContainer
import com.qualityverifier.domain.AppId

class QualityVerifierApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The base URL is passed in rather than read inside :core, which is shared with
        // Fundi Bora and has no BuildConfig of its own. Still compiled in, still Kagua's.
        container = AppContainer(
            context = this,
            baseUrl = BuildConfig.SERVER_BASE_URL,
            appId = AppId.KAGUA,
            versionCode = BuildConfig.VERSION_CODE,
            // A debug build sends its version but is not prompted to update.
            checkForUpdates = !BuildConfig.DEBUG,
        )
        // At start, so the banner is ready before the home screen is. A second check
        // follows each sign-in; see AppContainer.
        container.appVersionCheck.check()
    }
}
