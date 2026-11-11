package com.qualityverifier.server.terms

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.db.AppSettings
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.routes.ErrorResponse
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger("com.qualityverifier.server.terms")

/**
 * Where an account stands with its app's terms.
 *
 * @property version the terms_version it must have agreed to; null when the app has no terms.
 * @property required true when either current agreement is missing, whether the gate is on
 *   or off (terms-screen plan, decision 4). The phone shows the terms screen on it, so
 *   agreements are collected before anything is refused.
 * @property transferAgreed whether the newest transfer row is an agreement, of any version.
 *   Settings offers withdrawal only while it is.
 * @property refuses true only when [required] and the app's terms_gate is on.
 */
data class TermsStatus(
    val version: String?,
    val required: Boolean,
    val transferAgreed: Boolean,
    val refuses: Boolean,
)

/**
 * The terms check: whether an account has agreed to its app's current terms, and whether
 * the server refuses it for not having done so.
 *
 * Both answers are cached per account for at most [maxAge], as settings are (V27), because
 * the check runs on every authenticated request. Agreeing or withdrawing calls [forget], so
 * the account's next request sees the change at once.
 *
 * **Missing text reads as no terms.** If an app's terms_version names a text the server
 * does not hold, nobody could read what they are asked to agree to, and a gate that
 * refused them would lock every phone out over a typo in a setting. So the app reads as
 * having no terms, the error is logged, and /healthz/deep reports it (see [missingTexts]).
 */
class TermsGate(
    private val settings: AppSettings,
    private val consents: ConsentStore,
    private val texts: TermsTexts,
    private val now: () -> Instant = Instant::now,
    private val maxAge: Duration = AppSettings.MAX_AGE,
) {
    private class Entry(val records: Map<ConsentKind, ConsentRecord>, val readAt: Instant)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** The terms_version [audience] must agree to, or null when it has none the server can show. */
    suspend fun currentVersion(audience: Audience): String? {
        val version = settings.termsVersion(audience) ?: return null
        if (texts.file(audience, version, TermsLanguage.ENGLISH) == null) {
            log.error("terms_version {} for {} has no English text; reading it as no terms", version, audience.id)
            return null
        }
        return version
    }

    suspend fun statusFor(userId: String, audience: Audience): TermsStatus {
        val version = currentVersion(audience)
            ?: return TermsStatus(version = null, required = false, transferAgreed = false, refuses = false)
        val records = recordsFor(userId)
        fun agreedNow(kind: ConsentKind) =
            records[kind]?.let { it.action == ConsentAction.AGREED && it.version == version } == true
        val required = !(agreedNow(ConsentKind.TERMS) && agreedNow(ConsentKind.TRANSFER))
        return TermsStatus(
            version = version,
            required = required,
            transferAgreed = records[ConsentKind.TRANSFER]?.action == ConsentAction.AGREED,
            refuses = required && settings.termsGateOn(audience),
        )
    }

    /** Whether any app's gate is on. Cached with the settings, so the common answer costs no query. */
    suspend fun anyGateOn(): Boolean = Audience.entries.any { settings.termsGateOn(it) }

    /** Forgets [userId]'s cached records, after it agrees or withdraws. */
    fun forget(userId: String) {
        cache.remove(userId)
    }

    /** "fundi 2026-12" for each app whose terms_version has no English text. Empty when all is well. */
    suspend fun missingTexts(): List<String> = Audience.entries.mapNotNull { audience ->
        val version = settings.termsVersion(audience) ?: return@mapNotNull null
        if (texts.file(audience, version, TermsLanguage.ENGLISH) == null) "${audience.id} $version" else null
    }

    private suspend fun recordsFor(userId: String): Map<ConsentKind, ConsentRecord> {
        val at = now()
        cache[userId]?.let { if (Duration.between(it.readAt, at) < maxAge) return it.records }
        val fresh = consents.current(userId)
        cache[userId] = Entry(fresh, at)
        return fresh
    }

    companion object {
        /**
         * The authenticated routes an account may use before it has agreed. Everything else
         * is refused, including any route added later, so a new route that collects data is
         * closed until somebody decides otherwise and adds it here.
         *
         * Reading one's own account and history, changing the password, deleting the
         * account, and the two terms routes. None collects new data, and the terms screen
         * must not hide a person's own assessments from them.
         */
        val OPEN: List<Pair<HttpMethod, Regex>> = listOf(
            HttpMethod.Get to "/v1/me",
            HttpMethod.Post to "/v1/me/consents",
            HttpMethod.Post to "/v1/me/consents/withdrawal",
            HttpMethod.Post to "/v1/auth/password",
            HttpMethod.Delete to "/v1/account",
            HttpMethod.Get to "/v1/sessions",
            HttpMethod.Get to "/v1/sessions/{}",
            HttpMethod.Delete to "/v1/sessions/{}",
            HttpMethod.Get to "/v1/blobs/{}",
            HttpMethod.Head to "/v1/blobs/{}",
            HttpMethod.Get to "/v1/fundi/profile",
            HttpMethod.Get to "/v1/fundi/workers",
        ).map { (method, template) ->
            method to Regex("^" + template.split("{}").joinToString("[^/]+") { Regex.escape(it) } + "$")
        }

        fun isOpen(method: HttpMethod, path: String): Boolean {
            val trimmed = path.trimEnd('/').ifEmpty { "/" }
            return OPEN.any { (m, pattern) -> m == method && pattern.matches(trimmed) }
        }
    }
}

class TermsGateConfig {
    var gate: TermsGate? = null
    var users: AuthStore? = null
}

/**
 * Refuses an account that has not agreed to its app's current terms with
 * `403 terms_required`, on every authenticated route not in [TermsGate.OPEN].
 *
 * Installed once, on the routing root, and run after authentication: a request without a
 * JWT principal is not this check's business (sign-in, the health checks, the portal, the
 * data API), and a request whose token failed has already been answered 401.
 *
 * With every app's gate off, it answers nothing and costs no query.
 */
val TermsGatePlugin = createRouteScopedPlugin("TermsGate", ::TermsGateConfig) {
    val gate = requireNotNull(pluginConfig.gate) { "TermsGatePlugin needs a gate" }
    val users = requireNotNull(pluginConfig.users) { "TermsGatePlugin needs the auth store" }
    on(AuthenticationChecked) { call ->
        val userId = call.principal<JWTPrincipal>()?.subject ?: return@on
        if (!gate.anyGateOn()) return@on
        if (TermsGate.isOpen(call.request.httpMethod, call.request.path())) return@on
        // A missing or disabled account is left to the route, which answers 401 as before.
        val user = users.findUser(userId)?.takeUnless { it.disabled } ?: return@on
        if (gate.statusFor(user.id, user.audience).refuses) {
            call.respond(HttpStatusCode.Forbidden, ErrorResponse("terms_required"))
        }
    }
}
