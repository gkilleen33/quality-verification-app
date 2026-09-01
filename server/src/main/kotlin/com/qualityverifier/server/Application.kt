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
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.PostgresAuthStore
import com.qualityverifier.server.routes.ErrorResponse
import com.qualityverifier.server.routes.authRoutes
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
    log.info("Kagua server {} starting on {}:{}", config.version, config.host, config.port)

    Runtime.getRuntime().addShutdownHook(Thread { database?.close() })

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config.version, database, auth)
    }.start(wait = true)
}

/** Bundled so the module signature does not grow a parameter per collaborator. */
class Auth(val store: AuthStore, val accessTokens: AccessTokens)

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
) {
    install(ContentNegotiation) { json() }
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
