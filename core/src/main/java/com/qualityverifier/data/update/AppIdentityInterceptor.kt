package com.qualityverifier.data.update

import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.AppVersionHeaders
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Names the app and its build on every request to our server.
 *
 * On the shared client rather than on each call, so no endpoint can forget it: the server
 * records the version on each new assessment (V24) and refuses an app below the minimum
 * with 426, and both only work if every request says what it is. Both apps are at the same
 * versionCode, so the app id travels with it.
 *
 * Headers, not body fields: the server's JSON reader refuses unknown keys, and an unknown
 * header is harmless to a server that predates it.
 */
class AppIdentityInterceptor(
    private val appId: AppId,
    private val versionCode: Int,
    /**
     * Called when the server answers 426: this build is below the minimum. The container
     * re-reads the config so the blocking screen appears without waiting for a restart.
     */
    private val onUpgradeRequired: () -> Unit = {},
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request().newBuilder()
            .header(AppVersionHeaders.APP, appId.id)
            .header(AppVersionHeaders.VERSION, versionCode.toString())
            .build()
        val response = chain.proceed(request)
        // Not for the config route itself, which the server never refuses: a 426 there
        // would mean a misconfigured server, and re-reading it would only ask again.
        if (response.code == UPGRADE_REQUIRED && !request.url.encodedPath.endsWith(CONFIG_PATH)) {
            onUpgradeRequired()
        }
        return response
    }

    private companion object {
        const val UPGRADE_REQUIRED = 426
        const val CONFIG_PATH = "v1/auth/config"
    }
}
