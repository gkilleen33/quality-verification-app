package com.qualityverifier.ui.settings

import com.qualityverifier.data.sync.PasswordOutcome
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the settings screen does with each answer from the server, in both apps.
 *
 * The rule the deletion tests hold: the caller signs out only when the server said the
 * account is gone. Signing out on a failed request would tell somebody their account was
 * deleted when it still exists.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeAccount(
        private val password: PasswordOutcome = PasswordOutcome.Changed,
        private val deleted: Boolean = true,
    ) {
        var passwordCalls = 0
            private set
        var deleteCalls = 0
            private set

        /** When set, deletion waits on it, so a test can act while the request is in flight. */
        var holdDelete: CompletableDeferred<Unit>? = null

        suspend fun changePassword(current: String, new: String): PasswordOutcome {
            passwordCalls++
            return password
        }

        suspend fun deleteAccount(): Boolean {
            deleteCalls++
            holdDelete?.await()
            return deleted
        }
    }

    private fun modelOf(account: FakeAccount) =
        SettingsViewModel(account::changePassword, account::deleteAccount)

    @Test
    fun `mismatched new passwords never reach the server`() = runTest {
        val account = FakeAccount()
        val model = modelOf(account)

        model.changePassword("old-password", "new-password", "new-pasword")
        advanceUntilIdle()

        assertEquals(0, account.passwordCalls)
        assertEquals("The two new passwords don't match.", model.message.value)
        assertFalse(model.passwordChanged.value)
    }

    @Test
    fun `a short new password never reaches the server`() = runTest {
        val account = FakeAccount()
        val model = modelOf(account)

        model.changePassword("old-password", "short", "short")
        advanceUntilIdle()

        assertEquals(0, account.passwordCalls)
        assertNotNull(model.message.value)
    }

    @Test
    fun `a changed password clears the message and the busy flag`() = runTest {
        val account = FakeAccount(password = PasswordOutcome.Changed)
        val model = modelOf(account)

        model.changePassword("old-password", "new-password", "new-password")
        advanceUntilIdle()

        assertEquals(1, account.passwordCalls)
        assertTrue(model.passwordChanged.value)
        assertNull(model.message.value)
        assertFalse(model.busy.value)
    }

    @Test
    fun `a wrong current password says so and changes nothing`() = runTest {
        val model = modelOf(FakeAccount(password = PasswordOutcome.WrongPassword))

        model.changePassword("wrong-password", "new-password", "new-password")
        advanceUntilIdle()

        assertFalse(model.passwordChanged.value)
        assertEquals("That current password isn't right.", model.message.value)
    }

    @Test
    fun `a deleted account is reported so the caller signs out`() = runTest {
        val model = modelOf(FakeAccount(deleted = true))

        model.deleteAccount()
        advanceUntilIdle()

        assertTrue(model.accountDeleted.value)
        assertNull(model.message.value)
        assertFalse(model.busy.value)
    }

    @Test
    fun `a failed deletion is not reported as deleted`() = runTest {
        val model = modelOf(FakeAccount(deleted = false))

        model.deleteAccount()
        advanceUntilIdle()

        assertFalse(model.accountDeleted.value)
        assertEquals(
            "Could not reach our server. Your account was not deleted.",
            model.message.value,
        )
    }

    @Test
    fun `busy while deletion is in flight, and a second tap sends nothing`() = runTest {
        val account = FakeAccount(deleted = true)
        val hold = CompletableDeferred<Unit>()
        account.holdDelete = hold
        val model = modelOf(account)

        model.deleteAccount()
        advanceUntilIdle()
        // The screen blocks back and disables its buttons on this flag; see SettingsScreen.
        assertTrue(model.busy.value)

        model.deleteAccount()
        model.changePassword("old-password", "new-password", "new-password")
        advanceUntilIdle()
        assertEquals(1, account.deleteCalls)
        assertEquals(0, account.passwordCalls)

        hold.complete(Unit)
        advanceUntilIdle()
        assertTrue(model.accountDeleted.value)
        assertFalse(model.busy.value)
    }
}
