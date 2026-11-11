package com.qualityverifier.server.routes

import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.server.AppVersions
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get

/**
 * `GET /v1/auth/config?app=kagua|fundi`: the latest and minimum versionCode of one app,
 * and where to download it.
 *
 * Mounted on its own rather than inside the auth routes, and with no token: it needs
 * neither a database nor a signing key, and a blocked app has to be able to read it even
 * when nothing else will answer it. The 426 rule exempts it for the same reason.
 *
 * The app comes from `?app=`, falling back to the X-App-Id header, so a request built by
 * hand to check a deploy can name the app it is asking about.
 */
fun Route.appConfigRoutes(versions: AppVersions) {
    get("/v1/auth/config") {
        val app = AppId.fromId(call.request.queryParameters["app"])
            ?: AppId.fromId(call.request.headers[AppVersionHeaders.APP])
        if (app == null) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse("invalid_request", "app must be kagua or fundi"),
            )
            return@get
        }
        val policy = versions.policyFor(app)
        call.respond(
            AppConfigResponse(
                latest = policy.latest,
                minimum = policy.minimum,
                downloadUrl = policy.downloadUrl,
            )
        )
    }
}
