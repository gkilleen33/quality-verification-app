package com.qualityverifier.auth

import com.qualityverifier.data.auth.LocalAccountGuard
import com.qualityverifier.data.auth.LocalAccountOwner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whose local data the phone holds, decided at each sign-in.
 *
 * Getting this wrong one way shows one maker another's pieces and pushes the first maker's
 * unsynced workers to the server under the second account. Getting it wrong the other way
 * deletes a returning maker's work that the server has never seen.
 */
class LocalAccountGuardTest {

    @Test
    fun `a first sign-in records the account and clears nothing`() {
        // Also an install from before the record existed. No evidence the data is anybody
        // else's, so a returning user's unsynced work is kept.
        val owner = FakeOwner(recorded = null)
        val guard = LocalAccountGuard(owner) { owner.clears++ }

        assertFalse(guard.beforeSigningIn(MAKER_A))

        assertEquals(0, owner.clears)
        assertEquals(MAKER_A, owner.userId())
    }

    @Test
    fun `the same account signing back in keeps everything`() {
        val owner = FakeOwner(recorded = MAKER_A)
        val guard = LocalAccountGuard(owner) { owner.clears++ }

        assertFalse(guard.beforeSigningIn(MAKER_A))

        assertEquals(0, owner.clears)
        assertEquals(MAKER_A, owner.userId())
    }

    @Test
    fun `a different account clears the previous one's data, then is recorded`() {
        val owner = FakeOwner(recorded = MAKER_A)
        var recordedWhenCleared: String? = "not cleared"
        val guard = LocalAccountGuard(owner) {
            owner.clears++
            recordedWhenCleared = owner.userId()
        }

        assertTrue(guard.beforeSigningIn(MAKER_B))

        assertEquals(1, owner.clears)
        // Recorded only after the clear: a process killed mid-clear clears again next time
        // rather than believing the data is already B's.
        assertEquals(MAKER_A, recordedWhenCleared)
        assertEquals(MAKER_B, owner.userId())
    }

    @Test
    fun `a clear that fails records nothing`() {
        // The sign-in fails with it. Recording B here would leave A's data in place under
        // B's name, and the next sign-in as B would keep it.
        val owner = FakeOwner(recorded = MAKER_A)
        val guard = LocalAccountGuard(owner) { error("disk full") }

        runCatching { guard.beforeSigningIn(MAKER_B) }

        assertEquals(MAKER_A, owner.userId())
    }

    @Test
    fun `an upgraded install still signed in learns its owner from the tokens`() {
        // Without this, the first account switch after the upgrade would find nothing
        // recorded and keep the old account's data.
        val owner = FakeOwner(recorded = null)
        val guard = LocalAccountGuard(owner) { owner.clears++ }

        guard.adoptIfUnrecorded { MAKER_A }
        assertTrue(guard.beforeSigningIn(MAKER_B))

        assertEquals(1, owner.clears)
    }

    @Test
    fun `an existing record is not overwritten from the tokens, and none is invented`() {
        val recorded = FakeOwner(recorded = MAKER_A)
        LocalAccountGuard(recorded) {}.adoptIfUnrecorded { error("must not be read") }
        assertEquals(MAKER_A, recorded.userId())

        val signedOut = FakeOwner(recorded = null)
        LocalAccountGuard(signedOut) {}.adoptIfUnrecorded { null }
        assertNull(signedOut.userId())
    }

    // ---------------------------------------------------------------- harness

    private class FakeOwner(private var recorded: String?) : LocalAccountOwner {
        var clears = 0
        override fun userId() = recorded
        override fun record(userId: String) {
            recorded = userId
        }
    }

    private companion object {
        const val MAKER_A = "11111111-2222-3333-4444-555555555555"
        const val MAKER_B = "99999999-8888-7777-6666-555555555555"
    }
}
