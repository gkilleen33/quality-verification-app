package com.qualityverifier.server.api

import com.qualityverifier.server.admin.AdminStore
import com.qualityverifier.server.blobs.BlobStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.qualityverifier.server.api")

/** Never more than this per request, whatever was asked for. */
private const val MAX_LIMIT = 200
private const val DEFAULT_LIMIT = 50

/**
 * The read-only data API.
 *
 * Everything is behind an API key that reads the whole corpus, identifiers and photographs
 * included, because joining an assessment to who did it and looking at the pictures is what
 * the research is for. Two consequences shape this file:
 *
 * - **Read-only, structurally.** Only `get` is mounted. There is no write path to get wrong,
 *   and a key that leaks cannot alter or delete anything.
 * - **Every request is audited** against the key that made it, into the same log the portal
 *   writes to. With a credential this broad, "somebody read everything" is a far less useful
 *   record than "this key read this, at this time".
 *
 * Separate from the portal's routes rather than folded into them: those authenticate a
 * person with a second factor and a short session, these authenticate a string. Keeping the
 * two apart means a mistake in one cannot let the other in.
 */
fun Route.apiRoutes(
    keys: ApiKeyStore,
    store: ApiStore,
    admin: AdminStore,
    blobs: BlobStore,
) {
    route("/api/v1") {

        install(ApiNoStore)

        /**
         * Resolves the key, records the request, or answers 401.
         *
         * Returns null having already responded, so a caller writes
         * `val key = requireKey(keys, admin) ?: return@get` and cannot carry on by accident.
         */
        suspend fun RoutingContext.requireKey(): String? {
            val presented = call.apiKey()
            val id = presented?.let { keys.idFor(it) }
            if (id == null) {
                // No detail about which part was wrong. A key is either live or it is not,
                // and saying more only helps somebody probing.
                log.warn("Rejected an API request to {}", call.request.path())
                call.respond(HttpStatusCode.Unauthorized, ApiError("invalid_key"))
                return null
            }
            keys.markUsed(id)
            admin.audit(
                adminId = null,
                // The key's own id, not an admin's. Whoever created it is recorded on the
                // row, so the trail from a request back to a person is one join.
                adminEmail = "api-key:$id",
                action = "api-read",
                target = call.request.path(),
                detail = call.request.queryParameters.entries()
                    .joinToString("&") { "${it.key}=${it.value.joinToString(",")}" }
                    .takeIf { it.isNotEmpty() },
                ip = call.clientIp(),
            )
            return id
        }

        get("/users") {
            requireKey() ?: return@get
            val limit = call.limit()
            val offset = call.offset()
            // One more than asked for, so "is there another page" needs no count query.
            val rows = store.users(limit + 1, offset)
            call.respond(ApiPage(rows.take(limit), rows.size > limit, limit, offset))
        }

        get("/assessments") {
            requireKey() ?: return@get
            val limit = call.limit()
            val offset = call.offset()
            val rows = store.assessments(
                limit = limit + 1,
                offset = offset,
                userId = call.request.queryParameters["user"]?.takeIf { isUuidish(it) },
                itemTypeId = call.request.queryParameters["item"],
                testersOnly = call.request.queryParameters["testers"] == "1",
                updatedSince = call.request.queryParameters["updated_since"]?.toLongOrNull(),
            )
            call.respond(ApiPage(rows.take(limit), rows.size > limit, limit, offset))
        }

        get("/assessments/{id}") {
            requireKey() ?: return@get
            val id = call.parameters["id"].orEmpty()
            if (!isUuidish(id)) {
                call.respond(HttpStatusCode.NotFound, ApiError("no_such_assessment"))
                return@get
            }
            val detail = store.assessment(id)
            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, ApiError("no_such_assessment"))
                return@get
            }
            call.respond(detail)
        }

        get("/tester-feedback") {
            requireKey() ?: return@get
            val limit = call.limit()
            val offset = call.offset()
            val rows = store.testerFeedback(limit + 1, offset)
            call.respond(ApiPage(rows.take(limit), rows.size > limit, limit, offset))
        }

        /**
         * The photo bytes.
         *
         * Checked against `attachments` before the disk is touched. The store is
         * content-addressed, so the path parameter becomes a filename — without this,
         * "any 64 hex characters" is a request for any file in the blob directory.
         */
        get("/photos/{sha256}") {
            requireKey() ?: return@get
            val sha = call.parameters["sha256"].orEmpty()
            if (!BlobStore.isValidHash(sha)) {
                call.respond(HttpStatusCode.BadRequest, ApiError("invalid_hash"))
                return@get
            }
            if (!store.photoExists(sha)) {
                call.respond(HttpStatusCode.NotFound, ApiError("no_such_photo"))
                return@get
            }
            val bytes = blobs.read(sha)
            if (bytes == null) {
                // A row with no file. Reported rather than dressed up as a 404, because it
                // means the sweep and the database disagree and somebody should look.
                log.error("Photo {} is referenced but missing from disk", sha)
                call.respond(HttpStatusCode.NotFound, ApiError("photo_missing"))
                return@get
            }
            call.respondBytes(bytes, ContentType.Image.JPEG)
        }
    }
}

/**
 * Accepts the key as a bearer token or as `X-API-Key`.
 *
 * Both, because a research consumer is as likely to be a curl one-liner or an R script as a
 * library, and getting turned away over a header name is a poor first minute.
 */
private fun ApplicationCall.apiKey(): String? {
    request.headers["Authorization"]
        ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.removePrefix("Bearer ")?.removePrefix("bearer ")
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?.let { return it }
    return request.headers["X-API-Key"]?.trim()?.takeIf { it.isNotEmpty() }
}

private fun ApplicationCall.limit(): Int =
    request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, MAX_LIMIT) ?: DEFAULT_LIMIT

private fun ApplicationCall.offset(): Int =
    request.queryParameters["offset"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0

private fun ApplicationCall.clientIp(): String? =
    request.headers["X-Forwarded-For"]?.split(",")?.firstOrNull()?.trim()
        ?: request.local.remoteHost

private fun isUuidish(value: String): Boolean =
    runCatching { java.util.UUID.fromString(value) }.isSuccess

/**
 * `no-store` on every API response.
 *
 * These carry phone numbers, locations and photographs. A caching proxy between a
 * researcher's laptop and here holding a page of that on disk is not a risk worth taking
 * for a dataset that is pulled once.
 */
private val ApiNoStore = createRouteScopedPlugin("ApiNoStore") {
    onCall { call ->
        call.response.headers.append("Cache-Control", "no-store")
        call.response.headers.append("X-Content-Type-Options", "nosniff")
    }
}
