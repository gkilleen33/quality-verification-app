package com.qualityverifier.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.qualityverifier.data.auth.AuthClient
import com.qualityverifier.data.auth.EncryptedPrefsTokenStore
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.chat.ServerChatService
import com.qualityverifier.data.db.AppDatabase
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.data.location.LocationCapture
import com.qualityverifier.data.location.LocationPreference
import com.qualityverifier.data.session.RoomSessionRepository
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.sync.AccountActions
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.sync.SyncClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container. Deliberately not Hilt: the whole point of the
 * Phase 1 / Phase 2 split was that migrating the app meant editing this one file.
 *
 * **Phase 2, done.** What changed here and nowhere else:
 *  - [ChatService] is now [ServerChatService], posting one turn to our own server
 *    instead of the whole conversation to `api.anthropic.com`
 *  - the API key store is gone entirely, replaced by [TokenStore]
 *  - the prompt repository is gone: the server assembles the system prompt, so the phone
 *    no longer fetches protocols and cannot substitute one
 *
 * No ViewModel or screen changed for any of that, which was the claim being tested.
 *
 * **Shared by both apps**, which is why it lives in `:core`. It holds what Kagua and Fundi
 * Bora both need and nothing either one has to itself; an app that needs more composes
 * this rather than reimplementing it. The lookup that reaches it — `ui.appContainer` —
 * stays with each app, because it reads that app's own Application subclass.
 */
class AppContainer(
    context: Context,
    /**
     * Where the server is.
     *
     * A parameter rather than `BuildConfig.SERVER_BASE_URL`, which was the only thing in
     * this whole layer that knew which app it was compiled into. Each app still compiles
     * its own value in and passes it here, so the deployment story is unchanged: changing
     * the backend still means a release.
     */
    private val baseUrl: String,
) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        // Our server waits on Claude, so a turn can still take minutes. The nginx read
        // timeout in front of it is 180s; this has to outlast that or the phone gives up
        // on an answer that is on its way.
        .readTimeout(200, TimeUnit.SECONDS)
        .writeTimeout(200, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "quality_verifier.db",
    ).addMigrations(*AppDatabase.MIGRATIONS).build()

    val images: ImageFileStore = ImageFileStore(appContext)

    val tokenStore: TokenStore = EncryptedPrefsTokenStore(appContext)

    /** Whether assessments record where they were made. Chosen at sign-up, set in Settings. */
    val locationPreference = LocationPreference(appContext)

    /**
     * One fix per assessment, taken without the customer doing anything.
     *
     * Held here rather than created per screen so the preference and the capture cannot
     * disagree about whether recording is on.
     */
    val locationCapture = LocationCapture(appContext, locationPreference)

    val authClient: AuthClient = AuthClient(
        client = httpClient,
        store = tokenStore,
        baseUrl = baseUrl,
        json = json,
        // For the refresh_tokens row, so a lost handset can be identified when its
        // token is revoked. Model only; nothing that identifies a person.
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
    )

    private val tokenProvider = TokenProvider(
        store = tokenStore,
        refresher = authClient::refresh,
    )

    val sessionRepository: SessionRepository = RoomSessionRepository(
        dao = database.sessionDao(),
        images = images,
    )

    private val syncClient = SyncClient(
        client = httpClient,
        tokens = tokenProvider,
        baseUrl = baseUrl,
        json = json,
    )

    val chatService: ChatService = ServerChatService(
        client = httpClient,
        tokens = tokenProvider,
        images = images,
        sessionStart = sessionRepository::startOf,
        baseUrl = baseUrl,
        json = json,
    )

    val assessmentSync: AssessmentSync = AssessmentSync(
        client = syncClient,
        sessions = sessionRepository,
        images = images,
        tokens = tokenStore,
    )

    /** True when this account is one of our evaluators. Read from the cached profile. */
    val isTester: Boolean get() = tokenStore.isTester()

    val account: AccountActions = AccountActions(syncClient)

    /** Signs out locally. The refresh token stays revocable server-side regardless. */
    fun signOut() = tokenProvider.signOut()

    val isSignedIn: Boolean get() = tokenStore.isSignedIn()
}
