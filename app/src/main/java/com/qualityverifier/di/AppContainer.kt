package com.qualityverifier.di

import android.content.Context
import android.os.Build
import androidx.room.Room
import com.qualityverifier.BuildConfig
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
 */
class AppContainer(context: Context) {

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
        baseUrl = BuildConfig.SERVER_BASE_URL,
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
        baseUrl = BuildConfig.SERVER_BASE_URL,
        json = json,
    )

    val chatService: ChatService = ServerChatService(
        client = httpClient,
        tokens = tokenProvider,
        images = images,
        sessionStart = sessionRepository::startOf,
        baseUrl = BuildConfig.SERVER_BASE_URL,
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
