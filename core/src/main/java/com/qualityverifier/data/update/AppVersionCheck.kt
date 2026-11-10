package com.qualityverifier.data.update

import android.util.Log
import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.domain.VersionPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

@Serializable
private data class ConfigBody(
    val latest: Int = 1,
    val minimum: Int = 1,
    @SerialName("download_url") val downloadUrl: String = "",
)

/** What the home screen's banner and the blocking screen read. */
data class UpdateState(
    val prompt: UpdatePrompt,
    /** The release page; empty until the server has answered. */
    val downloadUrl: String,
) {
    companion object {
        val NONE = UpdateState(UpdatePrompt.NONE, "")
    }
}

/**
 * Asks the server which versions of this app it wants, and says what to show.
 *
 * Called when the app starts and after each sign-in. A failed call changes nothing: an
 * app that cannot reach the server cannot be told to update, and showing a banner or a
 * blocking screen on a guess would be worse than showing nothing.
 *
 * Held by the container rather than a view model, so the answer and a dismissed banner
 * last for the process: dismissing hides the banner until the next start, not until the
 * next screen.
 */
class AppVersionCheck(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val json: Json,
    private val appId: AppId,
    private val versionCode: Int,
    /**
     * False in debug builds. A developer's build is usually behind or ahead of the release
     * the server names, and a banner on every run of it would be noise. The headers are
     * still sent; only the prompt is skipped.
     */
    private val enabled: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
) {

    private val _state = MutableStateFlow(UpdateState.NONE)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var policy: VersionPolicy? = null
    private var bannerDismissed = false

    /** Starts a check in the background. Safe to call from anywhere, including the UI. */
    fun check() {
        if (!enabled) return
        scope.launch { refresh() }
    }

    /** One check, awaited. [check] is the fire-and-forget form; tests call this. */
    suspend fun refresh() {
        if (!enabled) return
        val fetched = fetch() ?: return
        synchronized(this) {
            policy = fetched
            publish()
        }
    }

    /** Hides the banner until the app next starts. Has no effect on the blocking screen. */
    fun dismissBanner() {
        synchronized(this) {
            bannerDismissed = true
            publish()
        }
    }

    private fun publish() {
        val current = policy ?: return
        _state.value = UpdateState(
            prompt = current.promptFor(versionCode, bannerDismissed),
            downloadUrl = current.downloadUrl,
        )
    }

    private suspend fun fetch(): VersionPolicy? = withContext(io) {
        val request = Request.Builder()
            .url(baseUrl + "v1/auth/config?app=" + appId.id)
            .get()
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "Version check got HTTP ${response.code}")
                    return@use null
                }
                val body = json.decodeFromString<ConfigBody>(response.body?.string().orEmpty())
                VersionPolicy(
                    latest = body.latest,
                    minimum = body.minimum,
                    // An empty URL would make the Download button do nothing, so the public
                    // release page stands in for it.
                    downloadUrl = body.downloadUrl.ifBlank { VersionPolicy.DEFAULT_DOWNLOAD_URL },
                )
            }
        } catch (e: Exception) {
            // Offline is the common case in a workshop. Nothing to show, nothing to retry:
            // the next start or sign-in asks again.
            Log.w(TAG, "Version check failed", e)
            null
        }
    }

    private companion object {
        const val TAG = "AppVersionCheck"
    }
}
