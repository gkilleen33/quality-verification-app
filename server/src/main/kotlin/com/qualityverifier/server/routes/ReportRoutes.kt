package com.qualityverifier.server.routes

import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.reports.ReportService
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The report id, so the phone can name the file and a bug report can name the row. */
const val REPORT_ID_HEADER = "X-Report-Id"

/**
 * The PDF reports (plan v7 §5.4 and §5.7): the preview the phone shows before "Send", and
 * the report itself, as bytes.
 *
 * Both behind the token and the Fundi Bora check, and both answer 404 for a piece that is
 * not this account's, as the rename route does: a piece id is a client-made uuid, and a
 * different answer would say which ids exist.
 */
fun Route.reportRoutes(reports: ReportService, auth: AuthStore) {
    authenticate("jwt") {

        get("/v1/fundi/pieces/{id}/reports/preview") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@get
            val pieceId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))

            // The same fields as the report's body, as query parameters, so the preview is
            // of exactly the report that "Send" will request.
            val request = call.requestFromQuery()
            when (val outcome = reports.preview(userId, pieceId, request)) {
                is ReportService.PreviewOutcome.Ready -> call.respond(outcome.preview)
                ReportService.PreviewOutcome.NoSuchPiece ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
                is ReportService.PreviewOutcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.code, outcome.detail))
            }
        }

        post("/v1/fundi/pieces/{id}/reports") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@post
            val pieceId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))

            val request = call.receive<ReportRequest>()
            when (val outcome = reports.issue(userId, pieceId, request)) {
                is ReportService.Outcome.Issued -> {
                    call.response.header(REPORT_ID_HEADER, outcome.reportId)
                    // 201: a row was written for this download, cached render or not.
                    call.respondBytes(outcome.bytes, ContentType.Application.Pdf, HttpStatusCode.Created)
                }
                ReportService.Outcome.NoSuchPiece ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
                is ReportService.Outcome.Invalid ->
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.code, outcome.detail))
                // Another render is under way and did not finish in time. The phone shows
                // the same "try again" as for a lost connection; a retry finds the cache.
                ReportService.Outcome.Busy ->
                    call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("busy"))
            }
        }
    }
}

/** `kind`, `parts` (comma-separated), and `session_id`, as the report body carries them. */
private fun ApplicationCall.requestFromQuery(): ReportRequest = ReportRequest(
    kind = request.queryParameters["kind"].orEmpty(),
    parts = request.queryParameters["parts"]
        ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        ?.takeIf { it.isNotEmpty() },
    sessionId = request.queryParameters["session_id"]?.takeIf { it.isNotBlank() },
)
