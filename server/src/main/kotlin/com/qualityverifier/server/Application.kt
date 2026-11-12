package com.qualityverifier.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import com.qualityverifier.server.blobs.BlobSweeper
import com.qualityverifier.server.blobs.PostgresReferencedHashes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import com.qualityverifier.server.db.FeedbackStore
import com.qualityverifier.server.db.PostgresFeedbackStore
import com.qualityverifier.server.api.ApiKeyStore
import com.qualityverifier.server.api.ApiStore
import com.qualityverifier.server.api.PostgresApiKeyStore
import com.qualityverifier.server.api.PostgresApiStore
import com.qualityverifier.server.api.apiRoutes
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.serialization.SerializationException
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
import com.qualityverifier.server.db.AppSettings
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.PostgresSettingsStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.FundiStore
import com.qualityverifier.server.db.PostgresChatStore
import com.qualityverifier.server.db.PostgresFundiStore
import com.qualityverifier.server.db.PostgresAuthStore
import com.qualityverifier.domain.Audience
import com.qualityverifier.server.routes.ErrorResponse
import com.qualityverifier.server.routes.MeTerms
import com.qualityverifier.server.routes.MeTermsLanguage
import com.qualityverifier.server.routes.appConfigRoutes
import com.qualityverifier.server.routes.authRoutes
import com.qualityverifier.server.routes.chatRoutes
import com.qualityverifier.server.routes.consentRoutes
import com.qualityverifier.server.routes.fundiRoutes
import com.qualityverifier.server.routes.mpesaRoutes
import com.qualityverifier.server.mpesa.CounterpartyCoder
import com.qualityverifier.server.mpesa.MpesaStore
import com.qualityverifier.server.mpesa.PostgresMpesaStore
import com.qualityverifier.server.routes.reportRoutes
import com.qualityverifier.server.routes.syncRoutes
import com.qualityverifier.server.routes.termsTextRoutes
import com.qualityverifier.server.db.PostgresReportStore
import com.qualityverifier.server.reports.PdfRenderer
import com.qualityverifier.server.reports.ReportCache
import com.qualityverifier.server.reports.ReportService
import com.qualityverifier.server.terms.ConsentStore
import com.qualityverifier.server.terms.PostgresConsentStore
import com.qualityverifier.server.terms.TermsGate
import com.qualityverifier.server.terms.TermsGatePlugin
import com.qualityverifier.server.terms.TermsTexts
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
            feedback = PostgresFeedbackStore(database.source),
            dailyAssessmentLimit = config.dailyAssessmentLimit,
            testerDailyAssessmentLimit = config.testerDailyAssessmentLimit,
            fundiDailyAssessmentLimit = config.fundiDailyAssessmentLimit,
        )
    } else {
        log.warn("Chat is disabled: needs a database and KAGUA_ANTHROPIC_API_KEY.")
        null
    }

    // Needs only a database. No Anthropic key and no session key, so a server that has
    // neither still keeps a maker's setup answers rather than losing them.
    val fundi = database?.let { Fundi(PostgresFundiStore(it.source)) }

    val admin = if (database != null && config.adminSessionKey != null) {
        Admin(
            store = PostgresAdminStore(database.source),
            blobs = BlobStore(File(config.dataDirectory, "blobs")),
            feedback = PostgresFeedbackStore(database.source),
            apiKeys = PostgresApiKeyStore(database.source),
            apiStore = PostgresApiStore(database.source),
            sessionKey = config.adminSessionKey,
        )
    } else {
        log.warn("Admin portal is disabled: needs a database and KAGUA_ADMIN_SESSION_KEY.")
        null
    }

    /*
     * The photo sweep, in this process rather than as a second systemd unit.
     *
     * It needs the database *and* write access to the blob volume. kagua-purge.service has
     * neither: it runs as postgres over peer authentication, deliberately so that a daily
     * timer needs no Parameter Store access and holds no secret. Giving it both would mean
     * a second credential path and a second thing to keep in step with the schema. This
     * process already has exactly what the sweep needs.
     *
     * Ordering against the SQL purge does not matter. If the sweep runs first, the rows it
     * would have orphaned are collected on the next run — which is the property that makes
     * sweeping rather than tracking the right shape here.
     */
    if (database != null) {
        val sweeper = BlobSweeper(
            PostgresReferencedHashes(database.source),
            BlobStore(File(config.dataDirectory, "blobs")),
        )
        sweepScope.launch {
            // Not immediately on boot: a deploy restarts this process, and a directory walk
            // competing with startup on a burstable instance is a poor first impression.
            delay(SWEEP_START_DELAY)
            while (true) {
                runCatching { sweeper.sweep() }
                    .onFailure { log.error("Blob sweep failed; will try again tomorrow", it) }
                delay(SWEEP_INTERVAL)
            }
        }
    }

    // The PDF reports (V32). Need the database for the piece and the row, and the blob
    // store for the photos; no Anthropic key, since a report is built from stored replies.
    // The fonts load here, so a missing resource stops the boot rather than the first report.
    val reports = database?.let {
        val cache = ReportCache(File(config.dataDirectory, "report-cache"))
        sweepScope.launch {
            delay(SWEEP_START_DELAY)
            while (true) {
                runCatching { cache.sweep() }
                    .onFailure { log.error("Report cache sweep failed; will try again in an hour", it) }
                delay(REPORT_SWEEP_INTERVAL)
            }
        }
        Reports(
            ReportService(
                store = PostgresReportStore(it.source),
                photos = BlobStore(File(config.dataDirectory, "blobs"))::read,
                cache = cache,
                renderer = PdfRenderer(),
            ),
        )
    }

    // The settings the server reads while it runs (V27). Without a database every setting
    // is at its default, which leaves the terms gate off.
    val settings = database?.let { AppSettings(PostgresSettingsStore(it.source)) }
        ?: AppSettings.UNCONFIGURED

    // The terms texts are read and checked against terms/index.txt here, before the server
    // listens. A text whose bytes differ from its pinned hash throws, and the server does
    // not start: serving it would make every agreement record a hash of unseen words.
    val terms = database?.let {
        val consents = PostgresConsentStore(it.source)
        val texts = TermsTexts.fromClasspath()
        Terms(gate = TermsGate(settings, consents, texts), consents = consents, texts = texts)
    }

    // Fundi Bora's M-Pesa uploads. Need the database and the HMAC key: without the key a
    // counterparty could only be stored in the clear, so the routes are not mounted and the
    // phones keep their data until they are. A key too short to trust is treated as none.
    val mpesa = if (database != null && config.mpesaHmacKey != null) {
        runCatching { CounterpartyCoder(config.mpesaHmacKey) }
            .onFailure { log.error("M-Pesa uploads are disabled: {}", it.message) }
            .getOrNull()
            ?.let { Mpesa(PostgresMpesaStore(database.source), it) }
    } else {
        log.warn("M-Pesa uploads are disabled: need a database and KAGUA_MPESA_HMAC_KEY.")
        null
    }

    log.info("Kagua server {} starting on {}:{}", config.version, config.host, config.port)
    if (config.dailyAssessmentLimit > 0) {
        log.info(
            "Daily assessment limit: {} per customer, {} per fundi, {} for evaluators",
            config.dailyAssessmentLimit,
            config.fundiDailyAssessmentLimit,
            config.testerDailyAssessmentLimit,
        )
    } else {
        log.warn("Daily assessment limit is DISABLED; every request is billed to us")
    }
    com.qualityverifier.domain.AppId.entries.forEach { app ->
        val policy = config.appVersions.policyFor(app)
        log.info("App versions for {}: latest {}, minimum {}", app.id, policy.latest, policy.minimum)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            sweepScope.cancel()
            database?.close()
        },
    )

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config.version, database, auth, chat, fundi, admin, config.appVersions, settings, terms, mpesa, reports)
    }.start(wait = true)
}

/**
 * Where the photo sweep runs. Its own scope so cancelling it on shutdown cannot take
 * anything else with it, and a supervisor job so a failed sweep does not kill the scope.
 */
private val sweepScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/** Five minutes after boot, so a deploy is not competing with a directory walk. */
private val SWEEP_START_DELAY = 5.minutes

/** Daily, matching the SQL purge it complements. */
private val SWEEP_INTERVAL = 24.hours

/** Hourly, so a cached report lives at most about a day (plan v7 §5.4). */
private val REPORT_SWEEP_INTERVAL = 1.hours

/** Every M-Pesa route starts with this. Their unreadable bodies are logged without the parser's message. */
internal const val MPESA_PATH_PREFIX = "/v1/fundi/mpesa"

/** Bundled so the module signature does not grow a parameter per collaborator. */
class Auth(val store: AuthStore, val accessTokens: AccessTokens)

/**
 * Fundi Bora's own endpoints. Only the setup profile so far.
 *
 * Its own bundle rather than a field on [Chat]: an assessment and a maker's workshop
 * details are answered at completely different times, and the chat bundle needs an
 * Anthropic key that this has no use for.
 */
class Fundi(val store: FundiStore)

/**
 * The terms: the published texts, the consent records, and the check that refuses an
 * account without a current agreement. Needs a database; absent without one, and then no
 * app has terms.
 */
class Terms(val gate: TermsGate, val consents: ConsentStore, val texts: TermsTexts) {

    /** What `/v1/me` says about [audience]'s current terms at [version]: each language and its hash. */
    fun describe(audience: Audience, version: String): MeTerms = MeTerms(
        version = version,
        languages = texts.languages(audience, version).mapNotNull { language ->
            texts.file(audience, version, language)?.let { file ->
                MeTermsLanguage(
                    language = language.id,
                    sha256 = file.sha256,
                    path = "terms/${audience.id}/$version/${language.id}",
                )
            }
        },
    )
}

/**
 * Fundi Bora's M-Pesa uploads (V29): the store and the coder that turns a counterparty into
 * a code before anything is stored. Absent without the HMAC key, and then not mounted.
 */
class Mpesa(val store: MpesaStore, val coder: CounterpartyCoder)

/** Fundi Bora's PDF reports (V32). Absent without a database, and then not mounted. */
class Reports(val service: ReportService)

/** The admin portal. Absent when no session key is configured, so it simply is not mounted. */
class Admin(
    val store: AdminStore,
    val blobs: BlobStore,
    val feedback: FeedbackStore,
    val apiKeys: ApiKeyStore,
    val apiStore: ApiStore,
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
    val feedback: FeedbackStore,
    /** Assessments one account may start per day. Zero or less means no limit. */
    val dailyAssessmentLimit: Int = Config.DEFAULT_DAILY_ASSESSMENT_LIMIT,
    /** The higher allowance for one of our own evaluators. */
    val testerDailyAssessmentLimit: Int = Config.DEFAULT_TESTER_DAILY_ASSESSMENT_LIMIT,
    /** The higher allowance for a maker assessing their own work. */
    val fundiDailyAssessmentLimit: Int =
        Config.DEFAULT_DAILY_ASSESSMENT_LIMIT * Config.FUNDI_LIMIT_MULTIPLE,
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
    fundi: Fundi? = null,
    admin: Admin? = null,
    /** Each app's latest and minimum versionCode. Defaulted to 1 and 1 for tests. */
    appVersions: AppVersions = AppVersions.UNCONFIGURED,
    /** The settings table, cached. Defaulted to every setting off for tests. Read through [terms]. */
    settings: AppSettings = AppSettings.UNCONFIGURED,
    /** The terms texts, records and gate. Null in tests that do not need them, and then no app has terms. */
    terms: Terms? = null,
    /** The M-Pesa uploads. Mounted only with auth and terms too: the routes check the mpesa_send agreement. */
    mpesa: Mpesa? = null,
    /** The PDF reports. Mounted only with auth, which every report route needs. */
    reports: Reports? = null,
) {
    install(ContentNegotiation) { json() }
    // Before routing, so it runs ahead of authentication. See installAppVersionGate.
    installAppVersionGate(appVersions)
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
        // A body the server could not read is the client's problem, not ours, and it has
        // to say so. Without this it fell through to the handler below and answered 500,
        // which told the phone "our server had a problem" — so a request the phone had
        // malformed came back looking like an outage, logged at error alongside real
        // ones. Found while testing that a client cannot smuggle an audience field: the
        // strict JSON refuses the stray key, and the refusal arrived as a 500.
        //
        // Ktor wraps a deserialisation failure in BadRequestException; the bare
        // SerializationException is caught too, because which one surfaces depends on
        // where the parse happened and the distinction is not worth relying on.
        val badRequest: suspend (ApplicationCall, Throwable) -> Unit = { call, cause ->
            // warn, not error: a malformed request is routine and a client bug, and
            // logging it at error is how the log stops being worth reading.
            //
            // A JSON parse error's message quotes the input around the fault, and an M-Pesa
            // upload holds counterparties' numbers and names, which must never reach a log.
            // So those paths log only the kind of failure.
            val path = call.request.path()
            if (path.startsWith(MPESA_PATH_PREFIX)) {
                log.warn("Unreadable request on {}: {}", path, cause.javaClass.simpleName)
            } else {
                log.warn("Unreadable request on {}: {}", path, cause.message)
            }
            // No detail on the wire. The message can name a field or quote the body,
            // and request bodies here are photographs of people's homes.
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid_request"))
        }
        exception<BadRequestException> { call, cause -> badRequest(call, cause) }
        exception<SerializationException> { call, cause -> badRequest(call, cause) }

        exception<Throwable> { call, cause ->
            log.error("Unhandled failure on {}", call.request.path(), cause)
            // The message stays server-side. A stack trace on the wire tells an
            // attacker about the stack and tells the customer nothing.
            call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "internal_error"))
        }
    }

    routing {
        // Once, on the root, so it covers every authenticated route mounted below and any
        // added later. It runs after authentication and ignores requests without a token.
        // See TermsGatePlugin.
        if (auth != null && terms != null) {
            install(TermsGatePlugin) {
                gate = terms.gate
                users = auth.store
            }
        }

        // Outside the auth block: it needs no database and no key, and an app below the
        // minimum has to be able to read it whatever else is mounted.
        appConfigRoutes(appVersions)
        auth?.let { authRoutes(it.store, it.accessTokens, terms) }
        terms?.let { termsTextRoutes(it) }
        if (auth != null) terms?.let { consentRoutes(auth.store, it) }
        // Chat needs auth: every route inside it authenticates, and mounting them
        // without the plugin installed would fail at request time rather than here.
        if (auth != null) chat?.let {
            // Two endpoints, one per app, each hard-coded to its own prompt. The auth
            // store is here to refuse an account belonging to the other app — a guard,
            // not a way to pick a prompt.
            chatRoutes(
                it.store, auth.store, it.blobs, it.claude, it.prompts,
                it.dailyAssessmentLimit, it.testerDailyAssessmentLimit,
                it.fundiDailyAssessmentLimit,
            )
            // Reading assessments back, plus the two account actions. Needs both halves:
            // the chat store for sessions and the auth store for credentials.
            syncRoutes(it.store, auth.store, auth.accessTokens, it.blobs, it.feedback)
        }

        // Also behind auth, but deliberately not inside the `chat` block: the setup
        // answers are worth storing on a server with no Anthropic key configured, and
        // folding them in there would take them down with chat.
        if (auth != null) fundi?.let { fundiRoutes(it.store, auth.store) }

        // Needs the terms bundle as well as auth: whether an account may upload is its
        // mpesa_send agreement, which lives in the consent records.
        if (auth != null && terms != null) mpesa?.let { mpesaRoutes(it.store, it.coder, auth.store, terms) }

        // The reports, beside the other Fundi Bora routes and for the same reason outside
        // the chat block: they read stored replies and need no Anthropic key.
        if (auth != null) reports?.let { reportRoutes(it.service, auth.store) }

        admin?.let {
            adminRoutes(it.store, it.blobs, it.feedback, it.apiKeys, it.secureCookie)
            // The read-only data API. Mounted alongside the portal because it shares the
            // key store the portal manages — a key with no page to revoke it from is a
            // credential nobody can take back.
            apiRoutes(it.apiKeys, it.apiStore, it.store, it.blobs)
        }

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
            // A terms_version with no text reads as "no terms" (TermsGate), so nobody is
            // locked out by it; this is where the mistake shows instead.
            val missingTerms = terms?.gate?.missingTexts().orEmpty()
            database.check().fold(
                onSuccess = {
                    if (missingTerms.isEmpty()) {
                        call.respond(DeepHealth("ok", version, "ok", it))
                    } else {
                        log.error("terms_version without a text: {}", missingTerms)
                        call.respond(
                            HttpStatusCode.ServiceUnavailable,
                            DeepHealth("degraded", version, "ok", "$it; terms text missing: ${missingTerms.joinToString()}"),
                        )
                    }
                },
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
