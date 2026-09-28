package com.qualityverifier.fundi

import android.app.Application
import com.qualityverifier.di.AppContainer

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
        container = AppContainer(this, BuildConfig.SERVER_BASE_URL)
    }
}
