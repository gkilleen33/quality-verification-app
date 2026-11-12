package com.qualityverifier.fundi.mpesa

import com.qualityverifier.data.mpesa.ConsentOutcome
import com.qualityverifier.data.mpesa.MpesaAnswers
import com.qualityverifier.data.mpesa.MpesaSwitches
import com.qualityverifier.fundi.ui.mpesa.MpesaConsentViewModel
import com.qualityverifier.fundi.ui.mpesa.MpesaQuestion
import com.qualityverifier.text.MpesaLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

/** The two questions: read, then send, each with "Not now". */
@OptIn(ExperimentalCoroutinesApi::class)
class MpesaConsentViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val labels = MpesaLabels.ENGLISH

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a yes to reading records it at the moment of the yes, then asks about sending`() = runTest {
        val answers = FakeAnswers()
        val model = MpesaConsentViewModel(answers, now = { 42L })
        model.start(MpesaQuestion.READ, labels)

        model.agreeToReading()
        advanceUntilIdle()

        assertEquals(listOf("read at 42"), answers.calls)
        assertEquals(MpesaQuestion.SEND, model.question.value)
        assertFalse(model.done.value)
    }

    @Test
    fun `not now to reading ends without asking about sending or recording anything`() = runTest {
        val answers = FakeAnswers()
        val model = MpesaConsentViewModel(answers)
        model.start(MpesaQuestion.READ, labels)

        model.notNow()

        assertTrue(model.done.value)
        assertTrue(answers.calls.isEmpty())
    }

    @Test
    fun `a refused permission records nothing and says reading stays off`() = runTest {
        val answers = FakeAnswers()
        val model = MpesaConsentViewModel(answers)
        model.start(MpesaQuestion.READ, labels)

        model.permissionRefused()

        assertEquals(labels.permissionRefused, model.message.value)
        assertEquals(MpesaQuestion.READ, model.question.value)
        assertTrue(answers.calls.isEmpty())
    }

    @Test
    fun `offline, the question stays and says so`() = runTest {
        val answers = FakeAnswers(outcome = ConsentOutcome.Offline)
        val model = MpesaConsentViewModel(answers)
        model.start(MpesaQuestion.READ, labels)

        model.agreeToReading()
        advanceUntilIdle()

        assertEquals(MpesaQuestion.READ, model.question.value)
        assertEquals(labels.offline, model.message.value)
        assertFalse(model.busy.value)
    }

    @Test
    fun `a yes to sending finishes`() = runTest {
        val answers = FakeAnswers(reading = true)
        val model = MpesaConsentViewModel(answers)
        model.start(MpesaQuestion.SEND, labels)

        model.agreeToSending()
        advanceUntilIdle()

        assertEquals(listOf("send"), answers.calls)
        assertTrue(model.done.value)
        assertNull(model.message.value)
    }

    @Test
    fun `sending is never asked of a fundi who is not reading`() = runTest {
        val model = MpesaConsentViewModel(FakeAnswers(reading = false))

        model.start(MpesaQuestion.SEND, labels)

        assertEquals(MpesaQuestion.READ, model.question.value)
    }

    @Test
    fun `two taps send one answer`() = runTest {
        val answers = FakeAnswers()
        val model = MpesaConsentViewModel(answers)
        model.start(MpesaQuestion.READ, labels)

        model.agreeToReading()
        model.agreeToReading()
        advanceUntilIdle()

        assertEquals(1, answers.calls.size)
    }

    private class FakeAnswers(
        reading: Boolean = false,
        private val outcome: ConsentOutcome = ConsentOutcome.Agreed,
    ) : MpesaAnswers {
        val calls = mutableListOf<String>()
        override val switches: StateFlow<MpesaSwitches> =
            MutableStateFlow(MpesaSwitches(readingSince = if (reading) 1L else null, sending = false))

        override suspend fun agreeToReading(at: Long): ConsentOutcome {
            calls += "read at $at"
            return outcome
        }

        override suspend fun agreeToSending(): ConsentOutcome {
            calls += "send"
            return outcome
        }

        override suspend fun stopReading() = Unit
        override suspend fun stopSending() = Unit
    }
}
