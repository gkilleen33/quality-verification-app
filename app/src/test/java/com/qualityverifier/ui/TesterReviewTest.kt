package com.qualityverifier.ui

import android.net.Uri
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.images.ImageQuality
import com.qualityverifier.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.io.File
import java.util.UUID

/**
 * The evaluator questionnaire.
 *
 * Its value is entirely in when it appears and where the answers go. Offered to a customer
 * it is a confusing survey; offered to an evaluator and then lost to a dead connection it
 * is a walkthrough that produced no measurement, and nobody remembers three days later
 * whether the assistant confused a dowel with a tenon.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TesterReviewTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a customer is never asked`() = runTest {
        val model = model(FakeSessions(finished()), isTester = false)
        advanceUntilIdle()

        assertFalse(model.reviewDue.value)
    }

    @Test
    fun `an evaluator is asked once the assessment has a verdict`() = runTest {
        val model = model(FakeSessions(finished()), isTester = true)
        advanceUntilIdle()

        assertTrue(model.reviewDue.value)
    }

    @Test
    fun `an evaluator who already answered is not asked again`() = runTest {
        // Reopening a reviewed assessment should read as finished work, not a nag.
        val sessions = FakeSessions(finished())
        sessions.testerFeedback += LocalTesterFeedback("s1", "no", null, 5, 8, null)
        val model = model(sessions, isTester = true)
        advanceUntilIdle()

        assertFalse(model.reviewDue.value)
    }

    @Test
    fun `the answers are stored against this assessment`() = runTest {
        val sessions = FakeSessions(finished())
        val model = model(sessions, isTester = true)
        advanceUntilIdle()

        model.startReview()
        assertTrue("the questionnaire should be open", model.reviewing.value)
        model.submitReview("yes", "Called a dowel a tenon", 4, 7, "Good on the joints")
        advanceUntilIdle()

        val saved = sessions.testerFeedback.single()
        assertEquals("s1", saved.sessionId)
        assertEquals("yes", saved.mistakes)
        assertEquals("Called a dowel a tenon", saved.mistakesDetail)
        assertEquals(4, saved.adviceStars)
        assertEquals(7, saved.itemQuality)
        assertEquals("Good on the joints", saved.extraFeedback)
        // And the questionnaire closes and stops being offered.
        assertFalse(model.reviewing.value)
        assertFalse(model.reviewDue.value)
    }

    @Test
    fun `blank free text is stored as absent rather than empty`() = runTest {
        // An empty string in the research table reads as "they answered nothing", which is
        // different from "they were not asked".
        val sessions = FakeSessions(finished())
        val model = model(sessions, isTester = true)
        advanceUntilIdle()

        model.submitReview("no", "   ", 5, 9, "")
        advanceUntilIdle()

        val saved = sessions.testerFeedback.single()
        assertNull(saved.mistakesDetail)
        assertNull(saved.extraFeedback)
    }

    @Test
    fun `dismissing keeps the offer for next time`() = runTest {
        // "Not now" is not "never": the evaluator may be handing the phone back mid-visit.
        val sessions = FakeSessions(finished())
        val model = model(sessions, isTester = true)
        advanceUntilIdle()

        model.startReview()
        model.dismissReview()

        assertFalse(model.reviewing.value)
        assertTrue("the prompt must survive a dismissal", model.reviewDue.value)
        assertTrue(sessions.testerFeedback.isEmpty())
    }

    @Test
    fun `a customer cannot open the questionnaire even by asking`() = runTest {
        // startReview is reachable from the UI only via a card that is not drawn for a
        // customer, but the guard belongs in the view model rather than the screen.
        val model = model(FakeSessions(finished()), isTester = false)
        advanceUntilIdle()

        model.startReview()

        assertFalse(model.reviewing.value)
    }

    // ---------------------------------------------------------------- harness

    private fun finished() = listOf(
        ChatMessage(UUID.randomUUID().toString(), Role.USER, "I am buying this."),
        ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, "A verdict, in prose."),
    )

    private fun model(sessions: SessionRepository, isTester: Boolean) = ChatViewModel(
        sessionId = "s1",
        declaredItemType = ItemType.WOODEN_TABLE,
        sessions = sessions,
        chat = NoChat,
        images = FakeImages(),
        isTester = isTester,
        io = dispatcher,
    )

    private object NoChat : ChatService {
        override suspend fun send(
            sessionId: String,
            itemType: ItemType,
            history: List<ChatMessage>,
        ): ChatResult = error("these tests never send")
    }

    private class FakeSessions(existing: List<ChatMessage>) : SessionRepository {
        private val state = MutableStateFlow(existing)
        val testerFeedback = mutableListOf<LocalTesterFeedback>()

        override fun observeSummaries(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = state
        override suspend fun messagesOnce(sessionId: String) = state.value
        override suspend fun startOf(sessionId: String) =
            SessionStart(ItemType.WOODEN_TABLE, null, null)

        override suspend fun sessionExists(sessionId: String) = true
        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
        ) = Unit

        override suspend fun appendUserMessage(
            sessionId: String,
            text: String,
            attachments: List<Attachment>,
        ) = ChatMessage(UUID.randomUUID().toString(), Role.USER, text, attachments)
            .also { state.value = state.value + it }

        override suspend fun appendAssistantMessage(sessionId: String, text: String) =
            ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, text)
                .also { state.value = state.value + it }

        override suspend fun deleteMessage(messageId: String) = Unit
        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun pruneOrphanImages() = Unit
        override suspend fun knownSessions() = emptyMap<String, Long>()
        override suspend fun writeSynced(
            session: com.qualityverifier.data.session.SyncedSession,
            messages: List<com.qualityverifier.data.session.SyncedMessage>,
        ) = Unit
        override suspend fun pendingRemoteDeletes() = emptyList<String>()
        override suspend fun recordPendingRemoteDelete(sessionId: String) = Unit
        override suspend fun clearPendingRemoteDelete(sessionId: String) = Unit

        val dismissed = mutableSetOf<String>()
        override suspend fun dismissLocally(sessionId: String) { dismissed += sessionId }
        override suspend fun dismissedSessions(): Set<String> = dismissed

        override suspend fun recordTesterFeedback(feedback: LocalTesterFeedback) {
            testerFeedback.removeAll { it.sessionId == feedback.sessionId }
            testerFeedback += feedback
        }

        override suspend fun pendingTesterFeedback() = testerFeedback.toList()
        override suspend fun hasPendingTesterFeedback(sessionId: String) =
            testerFeedback.any { it.sessionId == sessionId }

        override suspend fun clearTesterFeedback(sessionId: String) {
            testerFeedback.removeAll { it.sessionId == sessionId }
        }
    }

    private class FakeImages : SessionImageStore {
        override fun newImageFile(sessionId: String): File =
            File.createTempFile("capture", ".jpg").apply { deleteOnExit() }

        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File) = true
        override fun delete(file: File) { file.delete() }
        override fun measureQuality(file: File): ImageQuality? = null
    }
}
