package com.qualityverifier.server

import com.qualityverifier.server.admin.AdminSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules that decide whether a cookie may read customer conversations.
 *
 * Each of these is a one-line mistake away from a portal that skips the second factor or
 * stays open forever, and neither would show up in ordinary use — a broken idle timeout
 * looks exactly like a working one to somebody who is actually using the page.
 */
class AdminSessionTest {

    private val now = 1_700_000_000_000L

    private fun session(
        secondFactorDone: Boolean = true,
        enrolling: Boolean = false,
        signedInAt: Long = now,
        lastSeenAt: Long = now,
    ) = AdminSession(
        adminId = "a1",
        email = "admin@example.com",
        secondFactorDone = secondFactorDone,
        enrolling = enrolling,
        signedInAt = signedInAt,
        lastSeenAt = lastSeenAt,
        csrfToken = AdminSession.newCsrfToken(),
    )

    @Test
    fun `a password alone is not a session`() {
        // The stage between the two forms. If this returned true, 2FA would be decorative.
        assertFalse(session(secondFactorDone = false).fullyAuthenticated(now))
    }

    @Test
    fun `an admin still enrolling cannot read anything`() {
        // They have proved a password and a code, but the account was created by somebody
        // else who therefore also saw the secret. Nothing is readable until enrolment is
        // finished and confirmed.
        assertFalse(session(enrolling = true).fullyAuthenticated(now))
    }

    @Test
    fun `a fresh session with both factors is good`() {
        assertTrue(session().fullyAuthenticated(now))
    }

    @Test
    fun `thirty minutes idle ends it`() {
        val idle = session(lastSeenAt = now - AdminSession.IDLE_TIMEOUT_MILLIS - 1)
        assertTrue(idle.expired(now))
        assertFalse(idle.fullyAuthenticated(now))
    }

    @Test
    fun `just inside the idle window survives`() {
        assertFalse(session(lastSeenAt = now - AdminSession.IDLE_TIMEOUT_MILLIS + 1000).expired(now))
    }

    @Test
    fun `twelve hours ends it however active it has been`() {
        // The point of the absolute cap: lastSeenAt is right now, so the idle check alone
        // would keep a stolen cookie alive indefinitely.
        val old = session(signedInAt = now - AdminSession.ABSOLUTE_TIMEOUT_MILLIS - 1, lastSeenAt = now)
        assertTrue(old.expired(now))
        assertFalse(old.fullyAuthenticated(now))
    }

    @Test
    fun `csrf tokens are compared exactly`() {
        val s = session()
        assertTrue(s.csrfMatches(s.csrfToken))
        assertFalse(s.csrfMatches(null))
        assertFalse(s.csrfMatches(""))
        assertFalse(s.csrfMatches(s.csrfToken.dropLast(1)))
        assertFalse(s.csrfMatches(s.csrfToken.dropLast(1) + "x"))
        // A prefix must not pass. A length check alone, or startsWith, would let it.
        assertFalse(s.csrfMatches(s.csrfToken.take(4)))
    }

    @Test
    fun `csrf tokens are unguessable and unique`() {
        val a = AdminSession.newCsrfToken()
        val b = AdminSession.newCsrfToken()
        assertNotEquals(a, b)
        // 32 bytes, url-safe base64, unpadded.
        assertTrue(a.length >= 42)
        assertTrue(a, a.none { it == '+' || it == '/' || it == '=' })
    }
}
