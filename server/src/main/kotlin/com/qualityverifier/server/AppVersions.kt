package com.qualityverifier.server

import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.VersionPolicy
import com.qualityverifier.server.routes.ErrorResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.qualityverifier.server.versions")

/**
 * Each app's latest and minimum `versionCode`, from the environment.
 *
 * Configuration rather than a table: the values change once per release, at the deploy
 * that ships it, and Nathan sets them in the same step. A table would need an admin page
 * or an SQL session for something that is already a deploy-time decision.
 */
class AppVersions(private val policies: Map<AppId, VersionPolicy>) {

    fun policyFor(app: AppId): VersionPolicy = policies[app] ?: VersionPolicy.UNCONFIGURED

    /**
     * Whether a request from [appHeader] at [versionHeader] is refused with 426.
     *
     * Both headers have to be readable for a refusal. No version is an app older than the
     * header; a version without an app id cannot be compared with either app's minimum.
     * Neither is ever refused, so a malformed header degrades to being served.
     */
    fun refuses(appHeader: String?, versionHeader: String?): Boolean {
        val app = AppId.fromId(appHeader) ?: return false
        return policyFor(app).refuses(AppVersionHeaders.parseVersion(versionHeader))
    }

    companion object {
        /** Nothing set: latest = minimum = 1 for both apps, so nothing is prompted or refused. */
        val UNCONFIGURED = AppVersions(emptyMap())

        /**
         * `KAGUA_APP_LATEST_<APP>`, `KAGUA_APP_MINIMUM_<APP>` and
         * `KAGUA_APP_DOWNLOAD_URL_<APP>`, with `<APP>` `KAGUA` or `FUNDI`.
         *
         * An unreadable or missing number reads as 1, never as something higher: a typo in
         * the minimum must not lock every phone out. A minimum above latest raises latest to
         * match, so a blocked phone is never also told it is up to date.
         */
        fun fromEnvironment(env: (String) -> String?): AppVersions =
            AppVersions(
                AppId.entries.associateWith { app ->
                    val suffix = app.id.uppercase()
                    val minimum = env("KAGUA_APP_MINIMUM_$suffix").positiveOrOne()
                    val latest = maxOf(env("KAGUA_APP_LATEST_$suffix").positiveOrOne(), minimum)
                    VersionPolicy(
                        latest = latest,
                        minimum = minimum,
                        downloadUrl = env("KAGUA_APP_DOWNLOAD_URL_$suffix")?.trim()
                            ?.takeIf { it.isNotEmpty() }
                            ?: VersionPolicy.DEFAULT_DOWNLOAD_URL,
                    )
                }
            )

        private fun String?.positiveOrOne(): Int = this?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 1
    }
}

/** 426. Built rather than named, so it does not depend on the constant's spelling in Ktor. */
val UPGRADE_REQUIRED = HttpStatusCode(426, "Upgrade Required")

/**
 * Paths an app below the minimum may still reach.
 *
 * The config route, so a blocked app can learn it is blocked and where to download from.
 * Signing in, registering and refreshing, so an outdated install keeps its account and the
 * person is not signed out by the block. The health checks, which no phone calls but which
 * a deploy must never see refused.
 */
private val ALWAYS_SERVED = setOf(
    "/v1/auth/config",
    "/v1/auth/sign-in",
    "/v1/auth/register",
    "/v1/auth/refresh",
    "/healthz",
    "/healthz/deep",
)

/** Whether [call] is refused by [versions]. Separate from the pipeline so the rule reads alone. */
fun refusesOutdatedApp(call: ApplicationCall, versions: AppVersions): Boolean {
    if (call.request.path().trimEnd('/') in ALWAYS_SERVED) return false
    return versions.refuses(
        appHeader = call.request.headers[AppVersionHeaders.APP],
        versionHeader = call.request.headers[AppVersionHeaders.VERSION],
    )
}

/**
 * Answers 426 to an app below its minimum, before routing and before authentication.
 *
 * Before authentication on purpose: an outdated app is refused for what it is, not for
 * who is using it, and answering 401 first would send it into a sign-in loop that cannot
 * succeed. The admin portal and the data API send no version header and so are never
 * refused, without needing a rule of their own.
 */
fun Application.installAppVersionGate(versions: AppVersions) {
    intercept(ApplicationCallPipeline.Plugins) {
        val call: ApplicationCall = context
        if (refusesOutdatedApp(call, versions)) {
            log.info(
                "Refused {} from {} {}: below the minimum version",
                call.request.path(),
                call.request.headers[AppVersionHeaders.APP],
                call.request.headers[AppVersionHeaders.VERSION],
            )
            call.respond(
                UPGRADE_REQUIRED,
                ErrorResponse("upgrade_required", "This version of the app is no longer supported."),
            )
            finish()
        }
    }
}
