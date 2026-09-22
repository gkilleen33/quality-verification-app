package com.qualityverifier

import android.app.Application
import com.qualityverifier.di.AppContainer

class QualityVerifierApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // The base URL is passed in rather than read inside :core, which is shared with
        // Fundi Bora and has no BuildConfig of its own. Still compiled in, still Kagua's.
        container = AppContainer(this, BuildConfig.SERVER_BASE_URL)
    }
}
