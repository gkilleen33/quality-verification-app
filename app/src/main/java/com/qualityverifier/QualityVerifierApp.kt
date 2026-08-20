package com.qualityverifier

import android.app.Application
import com.qualityverifier.di.AppContainer

class QualityVerifierApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
