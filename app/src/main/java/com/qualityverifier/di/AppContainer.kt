package com.qualityverifier.di

import android.content.Context
import androidx.room.Room
import com.qualityverifier.BuildConfig
import com.qualityverifier.data.chat.AnthropicDirectChatService
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.AppDatabase
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.data.keys.ApiKeyStore
import com.qualityverifier.data.keys.EncryptedPrefsApiKeyStore
import com.qualityverifier.data.prompts.GitHubPromptRepository
import com.qualityverifier.data.prompts.PromptCache
import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.data.session.RoomSessionRepository
import com.qualityverifier.data.session.SessionRepository
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Hand-rolled dependency container. Deliberately not Hilt: the whole point of the
 * Phase 1 / Phase 2 split is that migrating the app means editing this one file.
 *
 * **Phase 2 migration checklist — all of it lives here:**
 *  - swap [AnthropicDirectChatService] for a server-proxy [ChatService]
 *  - swap [GitHubPromptRepository] for a server-backed [PromptRepository]
 *  - wrap [RoomSessionRepository] with server sync, or replace it
 *  - delete [apiKeyStore] and add a JWT store (also encrypted prefs)
 *
 * No UI or ViewModel code needs to change.
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
        // Vision requests on a slow connection routinely take longer than the default.
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val database: AppDatabase = Room.databaseBuilder(
        appContext,
        AppDatabase::class.java,
        "quality_verifier.db",
    ).build()

    val images: ImageFileStore = ImageFileStore(appContext)

    val apiKeyStore: ApiKeyStore = EncryptedPrefsApiKeyStore(appContext)

    val promptRepository: PromptRepository = GitHubPromptRepository(
        client = httpClient,
        cache = PromptCache(File(appContext.cacheDir, "prompts")),
        baseUrl = BuildConfig.PROMPT_BASE_URL,
    )

    val sessionRepository: SessionRepository = RoomSessionRepository(
        dao = database.sessionDao(),
        images = images,
    )

    val chatService: ChatService = AnthropicDirectChatService(
        client = httpClient,
        apiKeyStore = apiKeyStore,
        promptRepository = promptRepository,
        images = images,
        json = json,
    )
}
