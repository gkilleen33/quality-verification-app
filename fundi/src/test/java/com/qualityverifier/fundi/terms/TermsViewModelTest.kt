package com.qualityverifier.fundi.terms

import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.terms.AgreeOutcome
import com.qualityverifier.data.terms.TermsApi
import com.qualityverifier.data.terms.TermsInfo
import com.qualityverifier.data.terms.TermsLanguageInfo
import com.qualityverifier.data.terms.WithdrawOutcome
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.Workshop
import com.qualityverifier.fundi.ui.terms.AfterTerms
import com.qualityverifier.fundi.ui.terms.AgreementViewModel
import com.qualityverifier.fundi.ui.terms.TermsLoad
import com.qualityverifier.fundi.ui.terms.TermsViewModel
import com.qualityverifier.text.TermsLabels
import com.qualityverifier.text.sha256Hex
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

@OptIn(ExperimentalCoroutinesApi::class)
class TermsViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val text = "# Fundi Bora: Terms of Use\n\nVersion: 2026-11\n\n## 1. About\n\nText.\n".toByteArray()
    private val sha = sha256Hex(text)

    private fun required(languages: List<TermsLanguageInfo> = listOf(TermsLanguageInfo("en", sha, "terms/fundi/2026-11/en"))) =
        TermsInfo(required = true, version = "2026-11", languages = languages, transferAgreed = false)

    private class FakeApi(
        var info: TermsInfo?,
        var served: ByteArray? = null,
        var agreeOutcome: AgreeOutcome = AgreeOutcome.Agreed,
        var withdrawOutcome: WithdrawOutcome = WithdrawOutcome.Withdrawn,
    ) : TermsApi {
        val agreed = mutableListOf<Triple<String, String, String>>()
        var downloads = 0
        var withdrawals = 0

        override suspend fun status() = info
        override suspend fun text(path: String): ByteArray? {
            downloads++
            return served
        }
        override suspend fun agree(version: String, language: String, sha256: String): AgreeOutcome {
            agreed += Triple(version, language, sha256)
            return agreeOutcome
        }
        override suspend fun withdraw(): WithdrawOutcome {
            withdrawals++
            return withdrawOutcome
        }
    }

    private class FakeProfiles(private val outcome: ProfileOutcome) : FundiProfiles {
        override suspend fun load() = outcome
        override suspend fun save(profile: FundiProfile) = true
        override suspend fun saveWorkshopLocation(fix: LocationFix) = true
    }

    private fun model(
        api: FakeApi,
        profile: ProfileOutcome = ProfileOutcome.NotSetUpYet,
        bundled: ByteArray? = text,
    ) = TermsViewModel(api, FakeProfiles(profile)) { path ->
        bundled.takeIf { path == "fundi/2026-11/en.md" }
    }

    @Test
    fun `the bundled copy is shown without a download when its hash is the server's`() = runTest {
        val api = FakeApi(required())
        val model = model(api)

        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        val ready = model.load.value as TermsLoad.Ready
        assertEquals(sha, ready.sha256)
        assertEquals(0, api.downloads)
    }

    @Test
    fun `a bundled copy with another hash is replaced by the server's`() = runTest {
        val api = FakeApi(required(), served = text)
        val model = model(api, bundled = "an older text".toByteArray())

        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        assertTrue(model.load.value is TermsLoad.Ready)
        assertEquals(1, api.downloads)
    }

    @Test
    fun `a served copy whose hash does not match is never shown`() = runTest {
        val api = FakeApi(required(), served = "tampered".toByteArray())
        val model = model(api, bundled = null)

        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        assertEquals(TermsLoad.Failed, model.load.value)
    }

    @Test
    fun `offline with no answer from the server, the screen offers Retry`() = runTest {
        val model = model(FakeApi(info = null))

        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        assertEquals(TermsLoad.Failed, model.load.value)
    }

    @Test
    fun `Continue sends both agreements with the hash of the text shown, then setup for a new account`() = runTest {
        val api = FakeApi(required())
        val model = model(api, profile = ProfileOutcome.NotSetUpYet)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        model.agree(model.load.value as TermsLoad.Ready)
        advanceUntilIdle()

        assertEquals(listOf(Triple("2026-11", "en", sha)), api.agreed)
        assertEquals(AfterTerms.SETUP, model.done.value)
    }

    @Test
    fun `an account with a profile goes home after agreeing`() = runTest {
        val api = FakeApi(required())
        val model = model(api, profile = ProfileOutcome.Loaded(FundiProfile(workshop = Workshop())))
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        model.agree(model.load.value as TermsLoad.Ready)
        advanceUntilIdle()

        assertEquals(AfterTerms.HOME, model.done.value)
    }

    @Test
    fun `two taps send one agreement`() = runTest {
        val api = FakeApi(required())
        val model = model(api)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()
        val ready = model.load.value as TermsLoad.Ready

        model.agree(ready)
        model.agree(ready)
        advanceUntilIdle()

        assertEquals(1, api.agreed.size)
    }

    @Test
    fun `offline at Continue keeps the screen and says so`() = runTest {
        val api = FakeApi(required(), agreeOutcome = AgreeOutcome.Offline)
        val model = model(api)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        model.agree(model.load.value as TermsLoad.Ready)
        advanceUntilIdle()

        assertNull(model.done.value)
        assertEquals(TermsLabels.ENGLISH.offline, model.message.value)
        assertTrue(model.load.value is TermsLoad.Ready)
    }

    @Test
    fun `a changed text reloads with a new key, so the ticks start again`() = runTest {
        val api = FakeApi(required(), agreeOutcome = AgreeOutcome.TextChanged)
        val model = model(api)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()
        val first = model.load.value as TermsLoad.Ready

        model.agree(first)
        advanceUntilIdle()

        val second = model.load.value as TermsLoad.Ready
        assertTrue("the key must change so the latch and ticks reset", first.key != second.key)
        assertNull(model.done.value)
    }

    @Test
    fun `nothing to agree to moves straight on`() = runTest {
        val api = FakeApi(required().copy(required = false))
        val model = model(api, profile = ProfileOutcome.Loaded(FundiProfile(workshop = Workshop())))

        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        assertEquals(AfterTerms.HOME, model.done.value)
        assertTrue(api.agreed.isEmpty())
    }

    @Test
    fun `starting twice, as a rotation does, loads once`() = runTest {
        val api = object : TermsApi by FakeApi(required()) {
            var asked = 0
            override suspend fun status(): TermsInfo { asked++; return required() }
        }
        val model = TermsViewModel(api, FakeProfiles(ProfileOutcome.NotSetUpYet)) { text }

        model.start(TermsLabels.ENGLISH)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        assertEquals(1, api.asked)
    }

    @Test
    fun `the withdrawal section shows only while the transfer agreement is current`() = runTest {
        val agreed = AgreementViewModel(FakeApi(required().copy(required = false, transferAgreed = true)))
        val never = AgreementViewModel(FakeApi(required()))
        val offline = AgreementViewModel(FakeApi(info = null))

        listOf(agreed, never, offline).forEach { it.start(TermsLabels.ENGLISH) }
        advanceUntilIdle()

        assertEquals(true, agreed.agreed.value)
        assertEquals(false, never.agreed.value)
        assertNull("offline: hidden rather than guessed", offline.agreed.value)
    }

    @Test
    fun `a confirmed withdrawal calls the server once and leads to the terms screen`() = runTest {
        val api = FakeApi(required().copy(required = false, transferAgreed = true))
        val model = AgreementViewModel(api)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        model.withdraw()
        model.withdraw()
        advanceUntilIdle()

        assertEquals(1, api.withdrawals)
        assertTrue(model.withdrawn.value)
    }

    @Test
    fun `a withdrawal offline changes nothing and says so`() = runTest {
        val api = FakeApi(required().copy(required = false, transferAgreed = true), withdrawOutcome = WithdrawOutcome.Offline)
        val model = AgreementViewModel(api)
        model.start(TermsLabels.ENGLISH)
        advanceUntilIdle()

        model.withdraw()
        advanceUntilIdle()

        assertFalse(model.withdrawn.value)
        assertEquals(true, model.agreed.value)
        assertEquals(TermsLabels.ENGLISH.withdrawOffline, model.message.value)
    }
}
