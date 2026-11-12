package com.qualityverifier.server.routes

import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.reports.ReportService
import com.qualityverifier.server.reports.ReportTarget
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
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post

/** The report id, so the phone can name the file and a bug report can name the row. */
const val REPORT_ID_HEADER = "X-Report-Id"

/**
 * The PDF reports (plan v7 §5.4 and §5.7): the preview the phone shows before "Send", and
 * the report itself, as bytes. On a piece for every kind but one; on a linked roster
 * worker for the worker-work report.
 *
 * All behind the token and the Fundi Bora check, and all answer 404 for a piece or worker
 * that is not this account's, as the rename route does: the ids are client-made uuids, and
 * a different answer would say which ids exist.
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
            respondPreview(reports.preview(userId, ReportTarget.Piece(pieceId), call.requestFromQuery()))
        }

        post("/v1/fundi/pieces/{id}/reports") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@post
            val pieceId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
            respondReport(reports.issue(userId, ReportTarget.Piece(pieceId), call.receive<ReportRequest>()))
        }

        // The linked-worker report, from the worker's page: one roster worker's work across
        // pieces, offered only while the two-sided match holds.
        get("/v1/fundi/workers/{id}/reports/preview") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@get
            val workerId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_worker"))
            respondPreview(reports.preview(userId, ReportTarget.Worker(workerId), call.requestFromQuery()))
        }

        post("/v1/fundi/workers/{id}/reports") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@post
            val workerId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_worker"))
            respondReport(reports.issue(userId, ReportTarget.Worker(workerId), call.receive<ReportRequest>()))
        }
    }
}

private suspend fun RoutingContext.respondPreview(outcome: ReportService.PreviewOutcome) {
    when (outcome) {
        is ReportService.PreviewOutcome.Ready -> call.respond(outcome.preview)
        ReportService.PreviewOutcome.NoSuchPiece -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
        ReportService.PreviewOutcome.NoSuchSession -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_assessment"))
        ReportService.PreviewOutcome.NoSuchWorker -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_worker"))
        is ReportService.PreviewOutcome.NotLinked -> call.respond(HttpStatusCode.Forbidden, ErrorResponse(outcome.code))
        is ReportService.PreviewOutcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.code, outcome.detail))
    }
}

private suspend fun RoutingContext.respondReport(outcome: ReportService.Outcome) {
    when (outcome) {
        is ReportService.Outcome.Issued -> {
            call.response.header(REPORT_ID_HEADER, outcome.reportId)
            // 201: a row was written for this download, cached render or not.
            call.respondBytes(outcome.bytes, ContentType.Application.Pdf, HttpStatusCode.Created)
        }
        ReportService.Outcome.NoSuchPiece -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
        // A session_id that is not a listed assessment of this piece: 404, as a stranger's
        // session is everywhere else.
        ReportService.Outcome.NoSuchSession -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_assessment"))
        ReportService.Outcome.NoSuchWorker -> call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_worker"))
        // The link the kind needs does not hold. 403 with the code, so the phone can say
        // why rather than retry.
        is ReportService.Outcome.NotLinked -> call.respond(HttpStatusCode.Forbidden, ErrorResponse(outcome.code))
        is ReportService.Outcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.code, outcome.detail))
        // Another render is under way and did not finish in time. The phone shows the
        // same "try again" as for a lost connection; a retry finds the cache.
        ReportService.Outcome.Busy -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("busy"))
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
