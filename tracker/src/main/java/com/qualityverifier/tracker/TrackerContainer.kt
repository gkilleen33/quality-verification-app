package com.qualityverifier.tracker

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import com.qualityverifier.data.auth.AuthClient
import com.qualityverifier.data.auth.EncryptedPrefsTokenStore
import com.qualityverifier.data.auth.LocalAccountGuard
import com.qualityverifier.data.auth.PrefsLocalAccountOwner
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.terms.TermsApi
import com.qualityverifier.data.update.AppIdentityInterceptor
import com.qualityverifier.data.update.AppVersionCheck
import com.qualityverifier.domain.AppId
import com.qualityverifier.tracker.data.TrackerClient
import com.qualityverifier.tracker.data.TrackerDatabase
import com.qualityverifier.tracker.data.TrackerPreference
import com.qualityverifier.tracker.data.TrackerRepository
import com.qualityverifier.tracker.data.TrackerTermsClient
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * PesaLog's wiring. Hand-rolled, as :core's AppContainer is, and built from the same :core
 * parts: the token store and its single-flight refresh, the sign-in client, the identity
 * headers, the version check and the account guard. Not AppContainer itself, which opens
 * Kagua's and Fundi Bora's database and builds their assessment, roster and M-Pesa clients,
 * none of which this app has any use for.
 */
class TrackerContainer(
    context: Context,
    private val baseUrl: String,
    versionCode: Int,
    /** False in debug builds, which send the headers but skip the update prompt. */
    checkForUpdates: Boolean,
) {
    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // X-App-Id: tracker and this build's versionCode on every request, so the server
        // applies PesaLog's own latest and minimum (V33's AppId.TRACKER).
        .addInterceptor(AppIdentityInterceptor(AppId.TRACKER, versionCode) { appVersionCheck.check() })
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val database: TrackerDatabase =
        Room.databaseBuilder(appContext, TrackerDatabase::class.java, TrackerDatabase.NAME).build()

    private val prefs = TrackerPreference(appContext)

    val tokenStore: TokenStore = EncryptedPrefsTokenStore(appContext)

    /**
     * Clears the phone's records when a different account signs in: the payment log and the
     * two answers are one person's. The same guard as Kagua's and Fundi Bora's, in this app's
     * own sandbox.
     */
    private val localAccount = LocalAccountGuard(
        owner = PrefsLocalAccountOwner(appContext),
        clearLocalData = {
            database.clearAllTables()
            prefs.forget()
        },
    )

    val appVersionCheck: AppVersionCheck = AppVersionCheck(
        client = httpClient,
        baseUrl = baseUrl,
        json = json,
        appId = AppId.TRACKER,
        versionCode = versionCode,
        enabled = checkForUpdates,
    )

    val authClient: AuthClient = AuthClient(
        client = httpClient,
        store = tokenStore,
        baseUrl = baseUrl,
        json = json,
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        onSignedIn = { appVersionCheck.check() },
        beforeSaving = { userId ->
            if (localAccount.beforeSigningIn(userId)) {
                Log.i(TAG, "A different account signed in; cleared the previous one's records")
            }
        },
    )

    private val tokenProvider = TokenProvider(store = tokenStore, refresher = authClient::refresh)

    val repository = TrackerRepository(
        dao = database.dao(),
        prefs = prefs,
        api = TrackerClient(client = httpClient, tokens = tokenProvider, baseUrl = baseUrl, json = json),
    )

    /** PesaLog's own terms, through the interface the ported terms screen expects. */
    val terms: TermsApi = TrackerTermsClient(
        client = httpClient,
        tokens = tokenProvider,
        remember = prefs::rememberTerms,
        baseUrl = baseUrl,
        json = json,
    )

    val isSignedIn: Boolean get() = tokenStore.isSignedIn()

    /** PesaLog's own terms as the server last answered; null before it has. */
    val termsAgreed: Boolean? get() = prefs.termsAgreed

    /**
     * Signs out on this phone. The records stay, for the same account signing back in, and
     * go when a different account signs in. Reading stops with the sign-out: the listener
     * stores nothing while nobody is signed in (MoneyNotificationListener).
     *
     * A withdrawal made offline is sent first, for a few seconds at most, while the tokens
     * still work: the account guard forgets it if a different account signs in next, and the
     * server would then keep an agreement the person withdrew.
     */
    suspend fun signOut() {
        withTimeoutOrNull(SIGN_OUT_FLUSH_MILLIS) {
            runCatching { repository.sendWithdrawals() }
        }
        tokenProvider.signOut()
    }

    init {
        // An account signed in before the guard recorded owners is adopted, so a later
        // different sign-in still clears.
        localAccount.adoptIfUnrecorded(tokenStore::userId)
    }

    private companion object {
        const val TAG = "TrackerContainer"

        /** How long sign-out waits to send a pending withdrawal. */
        const val SIGN_OUT_FLUSH_MILLIS = 5_000L
    }
}
