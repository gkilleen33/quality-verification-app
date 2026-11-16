package com.qualityverifier.fundi.setup

import com.qualityverifier.data.fundi.BenchApi
import com.qualityverifier.data.fundi.BenchOutcome
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.domain.BenchDraft
import com.qualityverifier.domain.BenchTool
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import com.qualityverifier.fundi.ui.setup.SetupViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import com.qualityverifier.data.location.LocationSource
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

        val locations = mutableListOf<LocationFix>()
        override suspend fun saveWorkshopLocation(fix: LocationFix): Boolean {
            locations += fix
            return true
        }
    }

    private val shopFix = LocationFix(-1.2833, 36.8333, 15.0, capturedAt = 0L)

    @Test
    fun `nothing is located until they say they are at the shop and agree`() = runTest {
        var asked = 0
        val model = SetupViewModel(FakeProfiles(), locate = { asked++; shopFix })

        model.locateShop()
        model.answerAtShop(true)
        model.locateShop()
        advanceUntilIdle()
        assertEquals("no fix without consent", 0, asked)

        model.answerConsent(true)
        model.locateShop()
        advanceUntilIdle()
        assertEquals(1, asked)
        assertEquals(shopFix, model.shop.value.fix)
    }

    @Test
    fun `a recorded shop point is sent once with the profile`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles, locate = { shopFix })
        model.answerAtShop(true)
        model.answerConsent(true)
        model.locateShop()
        advanceUntilIdle()

        model.save()
        advanceUntilIdle()
        model.save()
        advanceUntilIdle()

        assertEquals(listOf(shopFix), profiles.locations)
    }

    @Test
    fun `saying no afterwards forgets the point`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles, locate = { shopFix })
        model.answerAtShop(true)
        model.answerConsent(true)
        model.locateShop()
        advanceUntilIdle()

        model.answerAtShop(false)
        model.save()
        advanceUntilIdle()

        assertNull(model.shop.value.fix)
        assertTrue(profiles.locations.isEmpty())
    }

    @Test
    fun `a refused permission or no fix says why`() = runTest {
        val model = SetupViewModel(FakeProfiles(), locate = { null })
        model.answerAtShop(true)
        model.answerConsent(true)

        model.onPermission(false)
        assertEquals(SetupViewModel.ShopLocation.Problem.NO_PERMISSION, model.shop.value.problem)

        model.onPermission(true)
        advanceUntilIdle()
        assertEquals(SetupViewModel.ShopLocation.Problem.NO_FIX, model.shop.value.problem)
    }

    @Test
    fun `an untouched tool is not sent at all`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.setTool(ToolKind.CHISELS, ToolOwnership.WORKER)
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

        model.setTool(ToolKind.PLANE_NO4, ToolOwnership.RENTAL)
        model.setTool(ToolKind.PLANE_NO4, ToolOwnership.WORKER)
        model.save()
        advanceUntilIdle()

        assertEquals(1, profiles.sent!!.tools.size)
        assertEquals(ToolOwnership.WORKER, profiles.sent!!.tools.single().ownership)
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

        model.setTool(ToolKind.HAND_SAW, ToolOwnership.WORKER)
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
            tools = listOf(OwnedTool(ToolKind.CHISELS, ToolOwnership.WORKER)),
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

    // ------------------------------------------------- the bench photo (v0.6.0, row 5)

    @get:Rule
    val folder = TemporaryFolder()

    private fun benchPhoto(): File = folder.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun benchModel(profiles: FakeProfiles, outcome: BenchOutcome, calls: MutableList<String> = mutableListOf()) =
        SetupViewModel(
            profiles,
            bench = BenchApi { photoId, _, _ -> calls += photoId; outcome },
            io = dispatcher,
        )

    private val drafted = BenchOutcome.Drafted(
        BenchDraft(listOf(BenchTool("clamps", "on the right"), BenchTool("chisels"), BenchTool("drill"))),
    )

    // The draft is Claude's guess from one photo. Only a tool the fundi confirms, and says
    // who owns, is set; a "no" leaves it unasked, not "do not have".
    @Test
    fun `a drafted tool is set only once confirmed and its owner given`() = runTest {
        val profiles = FakeProfiles()
        val model = benchModel(profiles, drafted)

        model.onBenchPhoto(benchPhoto())
        advanceUntilIdle()
        assertEquals(SetupViewModel.Bench.Confirm(drafted.draft.tools, 0, askingOwner = false), model.benchStep.value)

        model.confirmTool(true)                                   // clamps: yes ...
        model.chooseOwner(ToolOwnership.MANAGER)                  // ... my manager's
        model.confirmTool(false)                                  // chisels: no
        model.confirmTool(true)                                   // drill: yes, then nobody says whose
        model.skipBench()
        assertEquals(SetupViewModel.Bench.Done, model.benchStep.value)

        model.save()
        advanceUntilIdle()
        assertEquals(listOf(OwnedTool(ToolKind.CLAMPS, ToolOwnership.MANAGER)), profiles.sent!!.tools)
    }

    @Test
    fun `an owner that is not to hand is not an answer to who owns it`() = runTest {
        val model = benchModel(FakeProfiles(), drafted)
        model.onBenchPhoto(benchPhoto())
        advanceUntilIdle()
        model.confirmTool(true)
        model.chooseOwner(ToolOwnership.NONE)
        assertEquals(SetupViewModel.Bench.Confirm(drafted.draft.tools, 0, askingOwner = true), model.benchStep.value)
    }

    @Test
    fun `the sixth photo of the day says so, and a retry is the same photo`() = runTest {
        val calls = mutableListOf<String>()
        val model = benchModel(FakeProfiles(), BenchOutcome.LimitReached, calls)
        model.onBenchPhoto(benchPhoto())
        advanceUntilIdle()
        assertEquals(SetupViewModel.Bench.Failed(SetupViewModel.Bench.Reason.LIMIT), model.benchStep.value)

        model.retryBench()
        advanceUntilIdle()
        assertEquals(2, calls.size)
        assertEquals("a retry is counted once on the server", 1, calls.toSet().size)
    }

    // A fundi under a tin roof, with location allowed: the fix never comes. The photo goes
    // without a point after the short wait, rather than after a minute and a half.
    @Test
    fun `a slow GPS fix does not hold the bench photo`() = runTest {
        val sent = mutableListOf<LocationFix?>()
        val never = object : LocationSource {
            override val isAvailable = true
            override suspend fun capture(): LocationFix? = awaitCancellation()
        }
        val model = SetupViewModel(
            FakeProfiles(),
            bench = BenchApi { _, _, location -> sent += location; drafted },
            benchLocation = never,
            io = dispatcher,
        )
        model.onBenchPhoto(benchPhoto())
        advanceTimeBy(SetupViewModel.BENCH_FIX_WAIT_MILLIS + 1)
        runCurrent()
        assertEquals(listOf<LocationFix?>(null), sent)
        assertTrue(model.benchStep.value is SetupViewModel.Bench.Confirm)
    }

    @Test
    fun `a second tap while sending sends once`() = runTest {
        val calls = mutableListOf<String>()
        val model = benchModel(FakeProfiles(), BenchOutcome.Unavailable, calls)
        model.onBenchPhoto(benchPhoto())
        model.retryBench()
        advanceUntilIdle()
        assertEquals(1, calls.size)
    }

    @Test
    fun `skipping the bench photo sets nothing and asks nothing`() = runTest {
        val calls = mutableListOf<String>()
        val profiles = FakeProfiles()
        val model = benchModel(profiles, drafted, calls)
        model.skipBench()
        model.save()
        advanceUntilIdle()
        assertTrue(calls.isEmpty())
        assertTrue(profiles.sent!!.tools.isEmpty())
    }

    @Test
    fun `nothing is sent before Finish`() = runTest {
        val profiles = FakeProfiles()
        val model = SetupViewModel(profiles)

        model.updateWorkshop { it.copy(makes = "stools") }
        model.setTool(ToolKind.DRILL, ToolOwnership.WORKER)
        advanceUntilIdle()

        assertNull("the profile is sent whole, at the end", profiles.sent)
    }
}
