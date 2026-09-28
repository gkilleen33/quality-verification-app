package com.qualityverifier.fundi.setup

import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import com.qualityverifier.fundi.ui.setup.SetupViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What a half-answered setup flow actually sends.
 *
 * The rule under all of it: a tool absent from the profile means nobody asked, and
 * [ToolOwnership.NONE] means the maker said they have none. Only the second licenses the
 * coaching to work around it, and the difference survives all the way to the opening turn
 * of every assessment. A screen that defaulted unanswered toggles to "do not have" would
 * silently tell the model a maker owns nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SetupViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class FakeProfiles(
        private val existing: ProfileOutcome = ProfileOutcome.NotSetUpYet,
        private val succeeds: Boolean = true,
    ) : FundiProfiles {
        var sent: FundiProfile? = null
            private set
        var saves = 0
            private set

        override suspend fun load() = existing
        override suspend fun save(profile: FundiProfile): Boolean {
            saves++
            sent = profile
            return succeeds
        }
    }

    @Test
    fun `an untouched tool is not sent at all`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.setTool(ToolKind.CHISELS, ToolOwnership.OWNED)
        model.setTool(ToolKind.CLAMPS, ToolOwnership.NONE)
        model.save()
        advanceUntilIdle()

        val sent = profiles.sent!!
        assertEquals(
            "only the two that were answered",
            setOf(ToolKind.CHISELS, ToolKind.CLAMPS),
            sent.tools.map { it.kind }.toSet(),
        )
        // The one that matters: clamps were answered "none" and must be *named* as
        // absent, while the router nobody asked about must not appear at all.
        assertEquals(listOf(ToolKind.CLAMPS), sent.missing)
        assertTrue(sent.tools.none { it.kind == ToolKind.ROUTER })
    }

    @Test
    fun `changing an answer replaces it rather than adding a second`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.setTool(ToolKind.PLANE_NO4, ToolOwnership.BORROWED)
        model.setTool(ToolKind.PLANE_NO4, ToolOwnership.OWNED)
        model.save()
        advanceUntilIdle()

        assertEquals(1, profiles.sent!!.tools.size)
        assertEquals(ToolOwnership.OWNED, profiles.sent!!.tools.single().ownership)
    }

    @Test
    fun `a goal toggles off as well as on`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.toggleGoal(FundiGoal.MORE_ORDERS)
        model.toggleGoal(FundiGoal.ZERO_COMEBACKS)
        model.toggleGoal(FundiGoal.MORE_ORDERS)
        model.save()
        advanceUntilIdle()

        assertEquals(setOf(FundiGoal.ZERO_COMEBACKS), profiles.sent!!.goals)
    }

    @Test
    fun `an entirely skipped workshop still sends the tools`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.setTool(ToolKind.HAND_SAW, ToolOwnership.OWNED)
        model.save()
        advanceUntilIdle()

        assertFalse(profiles.sent!!.workshop.hasAnything)
        assertEquals(1, profiles.sent!!.tools.size)
    }

    // Re-entering setup edits rather than replaces, or a maker correcting one tool would
    // wipe the rest — and the server would read every wiped tool as still owned, because
    // an absent tool is deliberately not a disposal.
    @Test
    fun `existing answers are loaded before editing`() = runTest {
        val stored = FundiProfile(
            workshop = Workshop(makes = "stools"),
            tools = listOf(OwnedTool(ToolKind.CHISELS, ToolOwnership.OWNED)),
            goals = setOf(FundiGoal.MORE_ORDERS),
        )
        val profiles = FakeProfiles(existing = ProfileOutcome.Loaded(stored))
        val model = SetupViewModel(profiles)

        model.loadExisting()
        advanceUntilIdle()
        model.setTool(ToolKind.CLAMPS, ToolOwnership.NONE)
        model.save()
        advanceUntilIdle()

        val sent = profiles.sent!!
        assertEquals("stools", sent.workshop.makes)
        assertEquals(setOf(FundiGoal.MORE_ORDERS), sent.goals)
        assertEquals(
            setOf(ToolKind.CHISELS, ToolKind.CLAMPS),
            sent.tools.map { it.kind }.toSet(),
        )
    }

    // Offline at setup should not clear the form. An empty profile sent afterwards would
    // be a worse answer than the stale one already on the server.
    @Test
    fun `a failed load leaves the form alone`() = runTest {
        val model = SetupViewModel(FakeProfiles(existing = ProfileOutcome.Unavailable))

        model.updateWorkshop { it.copy(makes = "beds") }
        model.loadExisting()
        advanceUntilIdle()

        assertEquals("beds", model.workshop.value.makes)
    }

    @Test
    fun `a failed save says so and does not claim success`() = runTest {
        val model = SetupViewModel(FakeProfiles(succeeds = false))

        model.save()
        advanceUntilIdle()

        assertTrue(model.failed.value)
        assertFalse(model.saved.value)
    }

    // Double-tapping Finish must not write twice: the second write would compare against
    // the first and record a tool history of nothing changing, which is noise in the one
    // table the longitudinal claim rests on.
    @Test
    fun `saving twice while busy only sends once`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.save()
        model.save()
        advanceUntilIdle()

        assertEquals(1, profiles.saves)
    }

    @Test
    fun `nothing is sent before Finish`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.updateWorkshop { it.copy(makes = "stools") }
        model.setTool(ToolKind.DRILL, ToolOwnership.OWNED)
        advanceUntilIdle()

        assertNull("the profile is sent whole, at the end", profiles.sent)
    }
}
