package com.qualityverifier.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
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
    log.info("Kagua server {} starting on {}:{}", config.version, config.host, config.port)

    Runtime.getRuntime().addShutdownHook(Thread { database?.close() })

    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config.version, database)
    }.start(wait = true)
}

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

fun Application.module(version: String, database: DatabaseHealth?) {
    install(ContentNegotiation) { json() }
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
