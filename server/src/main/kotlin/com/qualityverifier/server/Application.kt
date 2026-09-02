package com.qualityverifier.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.sessions.SessionTransportTransformerMessageAuthentication
import io.ktor.server.sessions.Sessions
import io.ktor.server.sessions.cookie
import com.qualityverifier.server.admin.AdminSession
import com.qualityverifier.server.admin.AdminStore
import com.qualityverifier.server.admin.PostgresAdminStore
import com.qualityverifier.server.routes.adminRoutes
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import com.qualityverifier.data.prompts.GitHubPromptRepository
import com.qualityverifier.data.prompts.PromptCache
import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.AnthropicClient
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.PostgresChatStore
import com.qualityverifier.server.db.PostgresAuthStore
import com.qualityverifier.server.routes.ErrorResponse
import com.qualityverifier.server.routes.authRoutes
import com.qualityverifier.server.routes.chatRoutes
import com.qualityverifier.server.routes.syncRoutes
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import org.slf4j.event.Level

private val log = LoggerFactory.getLogger("com.qualityverifier.server")

fun main() {
    val config = Config.fromEnvironment()
    val database = config.database?.let(::Database)
    if (database == null) {
        log.warn("No database password in the environment: starting without a database.")
    }
    // Auth needs both a database to keep users in and a key to sign with. Missing
    // either means the auth routes are not mounted at all, rather than mounted and
    // failing per request — a 404 is a clearer signal of a misconfigured deployment
    // than a 500 on every sign-in.
    val auth = if (database != null && config.jwtSigningKey != null) {
        Auth(PostgresAuthStore(database.source), AccessTokens(config.jwtSigningKey))
    } else {
        log.warn("Auth is disabled: needs both a database and KAGUA_JWT_SIGNING_KEY.")
        null
    }
    // Prompts come from GitHub, fetched by the server rather than the phone, so a
    // client cannot substitute a system prompt and spend our budget on it. Same shared
    // repository the app uses, so the resolution order — fresh cache, network, stale
    // cache, compiled-in default — is the one already tested.
    val prompts: PromptRepository = GitHubPromptRepository(
        client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build(),
        cache = PromptCache(File(config.dataDirectory, "prompts")),
        baseUrl = config.promptBaseUrl,
        warn = { message, cause -> log.warn(message, cause) },
    )

    val chat = if (database != null && config.anthropicApiKey != null) {
        Chat(
            store = PostgresChatStore(database.source),
            blobs = BlobStore(File(config.dataDirectory, "blobs")),
            // Vision requests routinely run past a minute; the read timeout has to
            // outlast them or the client gives up on a call we are still paying for.
            claude = AnthropicClient(
                client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(180, TimeUnit.SECONDS)
                    .writeTimeout(180, TimeUnit.SECONDS)
                    .retryOnConnectionFailure(true)
                    .build(),
                apiKey = { config.anthropicApiKey },
            ),
            prompts = prompts,
            dailyAssessmentLimit = config.dailyAssessmentLimit,
        )
    } else {
        log.warn("Chat is disabled: needs a database and KAGUA_ANTHROPIC_API_KEY.")
        null
    }

    val admin = if (database != null && config.adminSessionKey != null) {
        Admin(
            store = PostgresAdminStore(database.source),
            blobs = BlobStore(File(config.dataDirectory, "blobs")),
            sessionKey = config.adminSessionKey,
        )
    } else {
        log.warn("Admin portal is disabled: needs a database and KAGUA_ADMIN_SESSION_KEY.")
        null
    }

    log.info("Kagua server {} starting on {}:{}", config.version, config.host, config.port)
    if (config.dailyAssessmentLimit > 0) {
        log.info("Daily assessment limit: {} per account", config.dailyAssessmentLimit)
    } else {
        log.warn("Daily assessment limit is DISABLED; every request is billed to us")
    }

    Runtime.getRuntime().addShutdownHook(Thread { database?.close() })

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config.version, database, auth, chat, admin)
    }.start(wait = true)
}

/** Bundled so the module signature does not grow a parameter per collaborator. */
class Auth(val store: AuthStore, val accessTokens: AccessTokens)

/** The admin portal. Absent when no session key is configured, so it simply is not mounted. */
class Admin(
    val store: AdminStore,
    val blobs: BlobStore,
    val sessionKey: String,
    /**
     * Whether the session cookie is marked Secure. True everywhere real.
     *
     * It exists only because a test client will not return a Secure cookie over http, and
     * the sign-in flow cannot be exercised without one coming back. Defaulting to true
     * means production gets the right behaviour unless somebody explicitly asks otherwise,
     * which is the only way round this worth having.
     */
    val secureCookie: Boolean = true,
)

class Chat(
    val store: ChatStore,
    val blobs: BlobStore,
    val claude: ClaudeClient,
    val prompts: PromptRepository,
    /** Assessments one account may start per day. Zero or less means no limit. */
    val dailyAssessmentLimit: Int = Config.DEFAULT_DAILY_ASSESSMENT_LIMIT,
)

@Serializable
data class Health(val status: String, val version: String, val uptimeSeconds: Long)

@Serializable
data class DeepHealth(
    val status: String,
    val version: String,
    val database: String,
    val detail: String? = null,
)

private val startedAt = System.currentTimeMillis()

fun Application.module(
    version: String,
    database: DatabaseHealth?,
    auth: Auth? = null,
    chat: Chat? = null,
    admin: Admin? = null,
) {
    install(ContentNegotiation) { json() }
    if (admin != null) {
        install(Sessions) {
            cookie<AdminSession>("kagua_admin") {
                cookie.path = "/admin"
                cookie.httpOnly = true
                // Set over TLS only. nginx terminates it, so a cookie without this could be
                // sent in clear by a browser that reached http:// once.
                cookie.secure = admin.secureCookie
                cookie.extensions["SameSite"] = "Strict"
                // No maxAge: a session cookie, gone when the browser closes. The timeouts
                // inside AdminSession are what actually enforce expiry, since a cookie's
                // own lifetime is a client-side suggestion.
                transform(SessionTransportTransformerMessageAuthentication(admin.sessionKey.toByteArray()))
            }
        }
    }
    if (auth != null) {
        install(Authentication) {
            jwt("jwt") {
                realm = "kagua"
                verifier(auth.accessTokens.verifier)
                validate { credential ->
                    // A signature that verifies but names nobody is not a credential.
                    credential.subject?.let { JWTPrincipal(credential.payload) }
                }
                challenge { _, _ ->
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
                }
            }
        }
    }
    install(CallLogging) {
        level = Level.INFO
        // Never the body. Request bodies here are photographs of people's homes and
        // workshops, and a log is the easiest place for them to leak.
        format { call -> "${call.request.local.method.value} ${call.request.path()} -> ${call.response.status()?.value}" }
    }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            log.error("Unhandled failure on {}", call.request.path(), cause)
            // The message stays server-side. A stack trace on the wire tells an
            // attacker about the stack and tells the customer nothing.
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }

    routing {
        auth?.let { authRoutes(it.store, it.accessTokens) }
        // Chat needs auth: every route inside it authenticates, and mounting them
        // without the plugin installed would fail at request time rather than here.
        if (auth != null) chat?.let {
            chatRoutes(it.store, it.blobs, it.claude, it.prompts, it.dailyAssessmentLimit)
            // Reading assessments back, plus the two account actions. Needs both halves:
            // the chat store for sessions and the auth store for credentials.
            syncRoutes(it.store, auth.store, it.blobs)
        }

        admin?.let { adminRoutes(it.store, it.blobs) }

        // Cheap and dependency-free, so a database outage does not make the service
        // look dead to whatever is watching it.
        get("/healthz") {
            call.respond(
                Health(
                    status = "ok",
                    version = version,
                    uptimeSeconds = (System.currentTimeMillis() - startedAt) / 1000,
                )
            )
        }

        // The one that actually proves something: secrets were retrieved, the pool
        // connects, and the schema is the version we think it is.
        get("/healthz/deep") {
            if (database == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    DeepHealth("degraded", version, "not configured"),
                )
                return@get
            }
            database.check().fold(
                onSuccess = { call.respond(DeepHealth("ok", version, "ok", it)) },
                onFailure = {
                    log.error("Database health check failed", it)
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        DeepHealth("degraded", version, "unreachable", it.javaClass.simpleName),
                    )
                },
            )
        }
    }
}
