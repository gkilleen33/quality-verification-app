package com.qualityverifier.server.routes

import com.qualityverifier.server.ShopReports
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.reports.ShopReportService
import com.qualityverifier.text.ReportPdfLabels
import io.ktor.http.ContentDisposition
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the tablet is given for the QR code (row 31). */
@Serializable
data class ReportLinkResponse(
    /** Relative to the server's base URL, which the tablet already holds (`SERVER_BASE_URL`). */
    val path: String,
    /** Epoch millis. */
    @SerialName("expires_at") val expiresAt: Long,
    @SerialName("max_downloads") val maxDownloads: Int,
)

/**
 * The tablet's half of row 31: a link to the customer's PDF, for the QR code.
 *
 * Behind the token. Only a shop account (V39), and only for its assessment in progress; a
 * personal Kagua account is refused with 403 `shop_tablet_only`, since it has Share and never
 * shows the code. Kagua has no terms, so the terms gate never refuses it; a Fundi Bora
 * account cannot be a shop and is refused here whatever the gate says.
 */
fun Route.shopReportLinkRoutes(reports: ShopReports, auth: AuthStore) {
    authenticate("jwt") {
        post("/v1/sessions/{id}/report-link") {
            val userId = call.principal<JWTPrincipal>()?.subject
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            val user = auth.findUser(userId)?.takeUnless { it.disabled }
                ?: return@post call.respond(HttpStatusCode.Unauthorized, ErrorResponse("invalid_token"))
            val id = call.parameters["id"].orEmpty()
            if (!isUuid(id)) return@post call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
            when (val outcome = reports.service.issue(userId, user.shopMode, id)) {
                is ShopReportService.IssueOutcome.Issued -> call.respond(
                    HttpStatusCode.Created,
                    ReportLinkResponse(outcome.path, outcome.expiresAt, ShopReportService.MAX_DOWNLOADS),
                )
                ShopReportService.IssueOutcome.NotAShopTablet ->
                    call.respond(HttpStatusCode.Forbidden, ErrorResponse("shop_tablet_only"))
                // An earlier customer's assessment gets the answer an unknown id gets.
                ShopReportService.IssueOutcome.NoSuchSession ->
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("no_such_session"))
                ShopReportService.IssueOutcome.NoVerdict ->
                    call.respond(HttpStatusCode.Conflict, ErrorResponse("no_verdict"))
                ShopReportService.IssueOutcome.TooMany ->
                    call.respond(HttpStatusCode.TooManyRequests, ErrorResponse("too_many_links"))
            }
        }
    }
}

/**
 * The customer's half of row 31: `GET /v1/r/{token}`, the PDF, with no sign-in.
 *
 * It serves that one PDF and nothing else: no listing, no redirect, and no page that says
 * whether a token ever existed. A wrong token, a used-up one and an expired one get the same
 * page, with the same status. Every answer, PDF or page, says not to cache it, not to send it
 * as a referrer, and not to index it.
 *
 * Limited per client address ([ShopReports.limit]). The address is nginx's `X-Real-IP`, which
 * the /v1/ location sets to `$remote_addr` and so overwrites anything a client sent; not the
 * first entry of `X-Forwarded-For`, which `$proxy_add_x_forwarded_for` appends to whatever the
 * client put there. Without nginx (tests, a local run) it is the direct peer.
 */
fun Route.shopReportPublicRoutes(reports: ShopReports) {
    get("/v1/r/{token}") {
        call.privateResponse()
        if (!reports.limit.tryAcquire(call.clientAddress())) {
            return@get call.respondText(busyPage, ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.TooManyRequests)
        }
        when (val outcome = reports.service.download(call.parameters["token"].orEmpty())) {
            is ShopReportService.DownloadOutcome.Pdf -> {
                call.response.header(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.Attachment.withParameter(ContentDisposition.Parameters.FileName, PDF_NAME).toString(),
                )
                call.respondBytes(outcome.bytes, ContentType.Application.Pdf, HttpStatusCode.OK)
            }
            ShopReportService.DownloadOutcome.Expired ->
                call.respondText(expiredPage, ContentType.Text.Html.withCharset(Charsets.UTF_8), HttpStatusCode.NotFound)
        }
    }
}

/** The file name the customer's phone saves. Names no shop, account or number. */
const val PDF_NAME = "kagua-report.pdf"

/** See [shopReportPublicRoutes]: nginx's `X-Real-IP`, else the direct peer. */
internal fun ApplicationCall.clientAddress(): String =
    request.headers["X-Real-IP"]?.trim()?.takeIf { it.isNotEmpty() } ?: request.local.remoteHost

private fun ApplicationCall.privateResponse() {
    response.header(HttpHeaders.CacheControl, "no-store")
    response.header("Referrer-Policy", "no-referrer")
    response.header("X-Robots-Tag", "noindex, nofollow")
    response.header("X-Content-Type-Options", "nosniff")
    response.header("Content-Security-Policy", "default-src 'none'")
}

/** English, then Kiswahili: a wrong token has no assessment, and so no language. */
internal val expiredPage: String = page(
    listOf(ReportPdfLabels.ENGLISH, ReportPdfLabels.SWAHILI).flatMap { listOf("h1" to it.linkExpiredTitle, "p" to it.linkExpiredBody) },
)

internal val busyPage: String = page(
    listOf(ReportPdfLabels.ENGLISH, ReportPdfLabels.SWAHILI).map { "p" to it.linkBusy },
)

private fun page(elements: List<Pair<String, String>>): String = buildString {
    append("<!doctype html><html><head><meta charset=\"utf-8\">")
    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
    append("<title>Kagua</title></head><body>")
    elements.forEach { (tag, text) -> append("<$tag>").append(escapeHtml(text)).append("</$tag>") }
    append("</body></html>")
}

private fun escapeHtml(text: String): String =
    text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
