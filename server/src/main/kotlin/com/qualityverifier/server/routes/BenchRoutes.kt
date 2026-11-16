package com.qualityverifier.server.routes

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.BenchDraft
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.Role
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.BenchRecord
import com.qualityverifier.server.db.BenchStore
import com.qualityverifier.server.db.ChatStore
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.security.MessageDigest

private val log = LoggerFactory.getLogger("com.qualityverifier.server.bench")

/** Bench photos a fundi may send per Nairobi day, outside the assessment allowance (Nathan, 4 November 2026). */
const val BENCH_PHOTOS_PER_DAY = 5

/**
 * The body of `POST /v1/fundi/bench-photos`: the phone's id for the photo, its hash (the
 * bytes uploaded first through `/v1/blobs`), and its GPS point when the fundi allows
 * location, all three or none.
 */
@Serializable
data class BenchPhotoRequest(
    val id: String = "",
    val sha256: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    @SerialName("accuracy_m") val accuracyMetres: Double? = null,
) {
    /** All three and in range, or null, as an assessment's point is read (ChatRequest). */
    val locationOrNull: SessionLocation?
        get() {
            val lat = latitude ?: return null
            val lon = longitude ?: return null
            val accuracy = accuracyMetres ?: return null
            val sane = lat in -90.0..90.0 && lon in -180.0..180.0 && accuracy > 0 && accuracy <= 5_000.0
            return if (sane) SessionLocation(lat, lon, accuracy) else null
        }
}

/**
 * The bench photo at setup (V35, release scope row 5): one Claude call drafts the tool list
 * from the photo, and the phone asks the fundi to confirm each tool and say who owns it.
 *
 * Nothing here writes a tool. The draft is returned and kept in `bench_photos`; the fundi's
 * confirmed list reaches `fundi_tools` only through `PUT /v1/fundi/profile`, as a typed list
 * does. The call is billed as `call_kind` 'bench' and counts against its own five a day,
 * never the assessment allowance.
 */
fun Route.benchRoutes(
    bench: BenchStore,
    chat: ChatStore,
    auth: AuthStore,
    blobs: BlobStore,
    claude: ClaudeClient,
    prompts: PromptRepository,
    dailyLimit: Int = BENCH_PHOTOS_PER_DAY,
) {
    authenticate("jwt") {
        post("/v1/fundi/bench-photos") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@post

            val request = call.receive<BenchPhotoRequest>()
            if (!isUuid(request.id) || !BlobStore.isValidHash(request.sha256)) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("invalid_request", "id must be a uuid and sha256 a hash"))
                return@post
            }
            // Refused before anything is recorded or spent, as a turn naming a missing photo is.
            val sha = request.sha256.lowercase()
            if (!blobs.exists(sha)) {
                call.respond(HttpStatusCode.Conflict, MissingBlobsResponse(missing = listOf(sha)))
                return@post
            }

            val photo = when (val recorded = bench.record(userId, request.id, sha, request.locationOrNull, dailyLimit)) {
                is BenchRecord.Ready -> recorded.photo
                BenchRecord.NotYours -> return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_photo"))
                is BenchRecord.LimitReached -> {
                    log.info("User {} reached the bench photo limit of {}", userId, recorded.limit)
                    return@post call.respond(
                        HttpStatusCode.TooManyRequests,
                        ErrorResponse("bench_limit_reached", "limit is ${recorded.limit} per day"),
                    )
                }
            }
            // A retry of a photo already drafted is answered from the row, never paid twice.
            photo.draftJson?.let { stored ->
                val draft = runCatching { json.decodeFromString(BenchDraft.serializer(), stored) }.getOrNull()
                if (draft != null) return@post call.respond(draft)
            }

            val prompt = prompts.benchPrompt()
            val turn = ChatMessage(
                id = "bench-${photo.id}",
                role = Role.USER,
                text = "The bench.",
                attachments = listOf(Attachment(id = photo.id, path = blobs.pathFor(sha).absolutePath)),
            )
            val started = System.currentTimeMillis()
            val result = claude.send(prompt, listOf(turn), { blobs.read(sha) })
            val elapsed = System.currentTimeMillis() - started
            when (result) {
                is ClaudeResult.Success -> {
                    chat.recordUsage(
                        userId, null, result.model, result.usage, httpStatus = 200, latencyMillis = elapsed,
                        errorKind = null, audience = Audience.FUNDI, callKind = BENCH_CALL_KIND,
                    )
                    // A reply with no readable list is an empty draft: the fundi types the list,
                    // as without a photo. Kept as such, so the accuracy check counts it.
                    val draft = BenchDraft.parse(result.text) ?: BenchDraft()
                    bench.saveDraft(photo.id, json.encodeToString(BenchDraft.serializer(), draft), sha256Of(prompt), result.model)
                    call.respond(draft)
                }
                is ClaudeResult.Failure -> {
                    chat.recordUsage(
                        userId, null, null, result.usage, httpStatus = result.httpStatus, latencyMillis = elapsed,
                        errorKind = result.error.name, audience = Audience.FUNDI, callKind = BENCH_CALL_KIND,
                    )
                    // The row stays without a draft; the phone's retry with the same id makes
                    // the call again without counting a second photo.
                    call.respond(HttpStatusCode.BadGateway, ErrorResponse("upstream_unavailable"))
                }
            }
        }
    }
}

/** `usage_events.call_kind` for a bench photo (V35). */
const val BENCH_CALL_KIND = "bench"

private val json = Json { encodeDefaults = true }

private fun sha256Of(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
