package com.qualityverifier.server.admin

import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64

/**
 * What the portal's cookie carries.
 *
 * Stateless by design: a server-side session table would be a second thing to expire, and
 * on one instance there is nothing to share it with. The cookie is signed, so nothing in
 * here can be edited by whoever holds it — but it is not encrypted, so nothing secret goes
 * in either. An email and an id are already known to the person holding the cookie.
 */
@Serializable
data class AdminSession(
    val adminId: String,
    val email: String,
    /** False until the TOTP code has been checked. Almost every route requires true. */
    val secondFactorDone: Boolean,
    /** Enrolment: set when this admin has yet to confirm their authenticator. */
    val enrolling: Boolean,
    /** Epoch millis of first sign-in, for the absolute cap. */
    val signedInAt: Long,
    /** Epoch millis of the last request, for the idle timeout. */
    val lastSeenAt: Long,
    /**
     * The CSRF token for this session, echoed back by every form.
     *
     * Kept in the cookie rather than server-side for the same reason as the rest: with a
     * signed cookie the token cannot be forged, and a token a form must repeat is exactly
     * what a cross-site POST cannot produce.
     */
    val csrfToken: String,
) {
    companion object {
        /**
         * Thirty minutes of inactivity.
         *
         * A portal that can read every customer's conversation should not stay open on an
         * unattended laptop, and admins here work in sittings rather than all day.
         */
        const val IDLE_TIMEOUT_MILLIS = 30 * 60 * 1000L

        /**
         * Twelve hours regardless of activity.
         *
         * A stolen cookie is otherwise good forever as long as something keeps touching it.
         */
        const val ABSOLUTE_TIMEOUT_MILLIS = 12 * 60 * 60 * 1000L

        private val random = SecureRandom()

        fun newCsrfToken(): String {
            val bytes = ByteArray(32).also(random::nextBytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }

    fun expired(now: Long): Boolean =
        now - lastSeenAt > IDLE_TIMEOUT_MILLIS || now - signedInAt > ABSOLUTE_TIMEOUT_MILLIS

    /** True only for a session that may see customer data. */
    fun fullyAuthenticated(now: Long): Boolean = secondFactorDone && !enrolling && !expired(now)

    fun csrfMatches(supplied: String?): Boolean {
        if (supplied == null || supplied.length != csrfToken.length) return false
        var difference = 0
        for (i in csrfToken.indices) difference = difference or (csrfToken[i].code xor supplied[i].code)
        return difference == 0
    }
}
