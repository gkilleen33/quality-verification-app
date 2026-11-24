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

        // "Reports sent" on a piece's page (V32, row 56): the reports this account was issued
        // on its own piece, newest first. A row means the server made the PDF and the phone
        // received it for the share sheet; whether the fundi then sent it is not known.
        get("/v1/fundi/pieces/{id}/reports") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@get
            val pieceId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
            val sent = reports.sentReports(userId, pieceId)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_piece"))
            call.respond(SentReportsDto(sent.map { SentReportDto(it.kind, it.issuedAt) }))
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

        // The worker's page under "My workers" (v0.6.0, rows 4, 11 and 22): the pieces a
        // report of this linked worker's work covers, the worker's own pieces labelled for
        // this studio among them, with the assessments this account may read. 404 for an
        // entry not on the roster, 403 while the match does not hold, as for the report.
        get("/v1/fundi/workers/{id}/pieces") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@get call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            if (!call.requireFundi(auth, userId)) return@get
            val workerId = call.parameters["id"]?.takeIf(::isUuid)
                ?: return@get call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_worker"))
            when (val outcome = reports.workerPieces(userId, workerId)) {
                is ReportService.WorkerPiecesOutcome.Ready -> call.respond(outcome.pieces)
                ReportService.WorkerPiecesOutcome.NoSuchWorker ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_worker"))
                ReportService.WorkerPiecesOutcome.NotLinked ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("worker_not_linked"))
            }
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
        ReportService.PreviewOutcome.FinalRequired -> call.respond(HttpStatusCode.Conflict, ErrorResponse(FINAL_REQUIRED))
        is ReportService.PreviewOutcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.code, outcome.detail))
    }
}

/**
 * A customer report asked for before the final assessment of the finished piece (row 27).
 * 409 with this code, so the phone offers the final assessment rather than an error.
 */
const val FINAL_REQUIRED = "final_assessment_required"

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
        ReportService.Outcome.FinalRequired -> call.respond(HttpStatusCode.Conflict, ErrorResponse(FINAL_REQUIRED))
        is ReportService.Outcome.Invalid -> call.respond(HttpStatusCode.BadRequest, ErrorResponse(outcome.code, outcome.detail))
        // Another render is under way and did not finish in time. The phone shows the
        // same "try again" as for a lost connection; a retry finds the cache.
        ReportService.Outcome.Busy -> call.respond(HttpStatusCode.ServiceUnavailable, ErrorResponse("busy"))
    }
}

/**
 * `kind`, `parts` and `piece_ids` (comma-separated), `session_id` and `studio_id`, as the
 * report body carries them.
 */
private fun ApplicationCall.requestFromQuery(): ReportRequest = ReportRequest(
    kind = request.queryParameters["kind"].orEmpty(),
    parts = request.queryParameters.list("parts"),
    sessionId = request.queryParameters["session_id"]?.takeIf { it.isNotBlank() },
    studioId = request.queryParameters["studio_id"]?.takeIf { it.isNotBlank() },
    pieceIds = request.queryParameters.list("piece_ids"),
)

private fun io.ktor.http.Parameters.list(name: String): List<String>? =
    this[name]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.takeIf { it.isNotEmpty() }
