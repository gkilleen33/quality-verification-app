package com.qualityverifier.di

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.Room
import com.qualityverifier.data.auth.AuthClient
import com.qualityverifier.data.auth.EncryptedPrefsTokenStore
import com.qualityverifier.data.auth.HeldTokenStore
import com.qualityverifier.data.auth.LocalAccountGuard
import com.qualityverifier.data.auth.PrefsLocalAccountOwner
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.chat.ServerChatService
import com.qualityverifier.data.db.AppDatabase
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.data.fundi.FundiProfileClient
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.location.LocationCapture
import com.qualityverifier.data.location.LocationPreference
import com.qualityverifier.data.session.RoomSessionRepository
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.sync.AccountActions
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.sync.SyncClient
import com.qualityverifier.data.update.AppIdentityInterceptor
import com.qualityverifier.data.update.AppVersionCheck
import com.qualityverifier.data.workers.RoomWorkerRepository
import com.qualityverifier.data.workers.WorkerRepository
import com.qualityverifier.domain.AppId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
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
    /**
     * Which assessment endpoint this app posts to.
     *
     * The second thing in this layer that differs between the apps, and for the same
     * reason as the first: the endpoint chooses the system prompt, so it is part of what
     * an app *is*. Defaulted to Kagua's, which is the safe direction to be wrong in — a
     * caller that forgets gets the buying prompt, and the server refuses it outright if
     * the account is a fundi's rather than quietly coaching a buyer.
     */
    private val chatPath: String = "v1/chat",
    /**
     * Which app this is, sent as X-App-Id. No default, unlike [chatPath]: both apps share
     * a versionCode, so a wrong id here would apply the other app's minimum to this one.
     */
    appId: AppId,
    /** The app's own BuildConfig.VERSION_CODE, sent as X-App-Version. */
    versionCode: Int,
    /** False in debug builds, which send the headers but skip the update prompt. */
    checkForUpdates: Boolean,
    /**
     * Whether the location switch counts only once the person has answered the question
     * (see [LocationPreference]). True in Fundi Bora, whose switch defaulted to on before
     * makers were asked. Defaulted to false, Kagua's, so Kagua is unchanged.
     */
    locationAnswerRequired: Boolean = false,
) {

    private val appContext = context.applicationContext

    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Every request says which app and which build it comes from. The server records
        // the build on each new assessment and refuses one below the minimum with 426;
        // on a 426 the config is read again so the blocking screen appears at once.
        .addInterceptor(AppIdentityInterceptor(appId, versionCode) { appVersionCheck.check() })
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

    /**
     * Whether assessments record where they were made. Chosen at sign-up, set in Settings.
     * Declared before [images], which reads it, and [localAccount], whose clear forgets it.
     */
    val locationPreference = LocationPreference(appContext, locationAnswerRequired)

    /**
     * Photos, and the camera metadata kept beside them. The location switch also decides
     * whether a photo's own GPS is kept: off means the camera's point is never sent either.
     */
    val images: ImageFileStore = ImageFileStore(appContext) { locationPreference.records }

    val tokenStore: TokenStore = EncryptedPrefsTokenStore(appContext)

    /**
     * Clears the local data when a different account signs in (see [LocalAccountGuard]).
     *
     * Every Room table and every photo: assessments, messages, pieces, the roster and all
     * the pending queues belong to the account that made them. In Fundi Bora the answer to
     * the location question does too, so it is forgotten and the new maker is asked on the
     * home screen; otherwise every assessment they made would record the previous maker's
     * yes. Kagua's location preference stays, as before (see [LocationPreference]).
     */
    private val localAccount = LocalAccountGuard(
        owner = PrefsLocalAccountOwner(appContext),
        clearLocalData = {
            database.clearAllTables()
            images.deleteAll()
            if (locationAnswerRequired) locationPreference.forget()
        },
    ).also {
        // An install from v0.3.0 still signed in: its tokens name the owner, so record it
        // now, before anybody can sign out and somebody else sign in.
        it.adoptIfUnrecorded(tokenStore::userId)
    }

    /**
     * One fix per assessment, taken without the customer doing anything.
     *
     * Held here rather than created per screen so the preference and the capture cannot
     * disagree about whether recording is on.
     */
    val locationCapture = LocationCapture(appContext, locationPreference)

    /**
     * Whether this build is behind the release, or below the minimum the server serves.
     * Checked when the app starts (each app's Application calls [AppVersionCheck.check])
     * and after each sign-in (below). The home screen shows the banner; each app's nav
     * shows the blocking screen.
     */
    val appVersionCheck: AppVersionCheck = AppVersionCheck(
        client = httpClient,
        baseUrl = baseUrl,
        json = json,
        appId = appId,
        versionCode = versionCode,
        enabled = checkForUpdates,
    )

    val authClient: AuthClient = AuthClient(
        client = httpClient,
        store = tokenStore,
        baseUrl = baseUrl,
        json = json,
        // For the refresh_tokens row, so a lost handset can be identified when its
        // token is revoked. Model only; nothing that identifies a person.
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})",
        onSignedIn = { appVersionCheck.check() },
        beforeSaving = { userId ->
            // A push from a sign-out moments ago may still be reading the queues. The clear
            // below would delete what it has not sent yet, so it finishes first. Bounded:
            // the push starts no request after SIGN_OUT_PUSH_MILLIS, and each request it
            // makes gives up after PUSH_CALL_SECONDS.
            signOutPush?.join()
            if (localAccount.beforeSigningIn(userId)) {
                Log.i(TAG, "A different account signed in; cleared the previous one's local data")
            }
        },
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
        chatPath = chatPath,
    )

    /** The manager's roster. Fundi Bora's; Kagua links it and never writes to it. */
    val workerRepository: WorkerRepository = RoomWorkerRepository(database.sessionDao())

    // The endpoint is what an app *is* (see chatPath), so it also says whether the app has
    // a roster.
    private val rosterEnabled = chatPath.startsWith("v1/fundi")

    val assessmentSync: AssessmentSync = AssessmentSync(
        client = syncClient,
        sessions = sessionRepository,
        images = images,
        tokens = tokenStore,
        workers = workerRepository,
        rosterEnabled = rosterEnabled,
    )

    /** True when this account is one of our evaluators. Read from the cached profile. */
    val isTester: Boolean get() = tokenStore.isTester()

    val account: AccountActions = AccountActions(syncClient)

    /**
     * The maker's setup answers. Only Fundi Bora constructs a screen that touches it;
     * Kagua links it and never calls it.
     *
     * Held here rather than built by that app so it goes through the same http client and
     * the same single-flight token provider as everything else. An app that assembled its
     * own would be one 401 away from spending a rotated refresh token twice.
     */
    val fundiProfiles: FundiProfiles = FundiProfileClient(
        client = httpClient,
        tokens = tokenProvider,
        baseUrl = baseUrl,
        json = json,
    )

    /**
     * Signs out locally. The refresh token stays revocable server-side regardless.
     *
     * Clears the tokens at once. The local data stays for the same account signing back
     * in, and is cleared at the next sign-in if that is somebody else (see [localAccount]).
     * That clear takes the queues the server has not received: pending deletes, evaluator
     * reviews, piece names, roster writes and session worker sets. So unless
     * [pushPendingWork] is false, the account's queues are pushed first, best effort, with
     * a copy of the tokens just cleared.
     *
     * The push runs in [background], not in the screen's scope: the caller navigates to
     * sign-in straight away, which would cancel a screen-scoped push before it sent
     * anything, and a busy spinner on Profile and Settings would keep somebody waiting on
     * a slow connection for work they cannot see. It uses the copy rather than the stored
     * tokens so that everything it sends goes as the account that made it, even if somebody
     * else signs in meanwhile; that sign-in waits for it (see `beforeSaving`).
     *
     * @param pushPendingWork false after the account has been deleted. The server has
     *   already revoked its refresh tokens, and its owner has just closed it: sending it
     *   new names, roster entries and reviews afterwards is not what they asked for.
     */
    fun signOut(pushPendingWork: Boolean = true) {
        val held = tokenProvider.signOutKeepingCopy() ?: return
        if (!pushPendingWork) return
        signOutPush = background.launch {
            val pushed = try {
                withTimeoutOrNull(SIGN_OUT_PUSH_MILLIS) { pushingAs(held).push() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never allowed to crash the app over work that stays on the phone anyway.
                Log.w(TAG, "Push at sign-out failed", e)
                return@launch
            }
            if (pushed == null) Log.i(TAG, "Push at sign-out ran out of time; the rest stays on the phone")
            else Log.i(TAG, "Pushed before sign-out: $pushed")
        }
    }

    /** Outlives every screen, so navigating away from sign-out does not cancel its push. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The latest push at sign-out, which the next sign-in waits for. */
    @Volatile private var signOutPush: Job? = null

    /**
     * Its own calls time out, unlike [httpClient]'s, which wait minutes for Claude.
     * Coroutine cancellation cannot interrupt a blocking OkHttp call, so without this one
     * slow request would hold the push, and a sign-in waiting on it, past its time limit.
     * Built from [httpClient] so it keeps the app identity headers and the connection pool.
     */
    private val pushHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder().callTimeout(PUSH_CALL_SECONDS, TimeUnit.SECONDS).build()
    }

    /**
     * An [AssessmentSync] that sends as [held], over [pushHttpClient].
     *
     * Its own token provider, so a refresh during the push saves into the copy and never
     * into the stored tokens, which by then belong to nobody or to the next account. Its
     * own refresh client too, used only for [AuthClient.refresh], so the refresh is bound
     * by the same call timeout.
     */
    private fun pushingAs(held: HeldTokenStore): AssessmentSync {
        val refresher = AuthClient(
            client = pushHttpClient,
            store = held,
            baseUrl = baseUrl,
            json = json,
            deviceName = "",
        )
        val tokens = TokenProvider(store = held, refresher = refresher::refresh)
        return AssessmentSync(
            client = SyncClient(client = pushHttpClient, tokens = tokens, baseUrl = baseUrl, json = json),
            sessions = sessionRepository,
            images = images,
            tokens = held,
            workers = workerRepository,
            rosterEnabled = rosterEnabled,
        )
    }

    val isSignedIn: Boolean get() = tokenStore.isSignedIn()

    private companion object {
        const val TAG = "AppContainer"

        /** How long the push at sign-out may start new requests. */
        const val SIGN_OUT_PUSH_MILLIS = 15_000L

        /** Each request in that push, end to end. */
        const val PUSH_CALL_SECONDS = 10L
    }
}
