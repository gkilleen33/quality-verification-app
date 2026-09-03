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
import kotlinx.coroutines.launch
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
import java.io.IOException
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

    @Test
    fun `a flag that arrives after the assessment started still opens the questionnaire`() =
        runTest {
            // The bug that shipped. The flag was captured at construction, and it is
            // fetched on sign-in — so somebody who registered with an evaluator code and
            // went straight into an assessment finished it with the phone still believing
            // they were a customer, and was never asked for the review.
            var tester = false
            val sessions = FakeSessions(finished())
            val model = ChatViewModel(
                sessionId = "s1",
                declaredItemType = ItemType.WOODEN_TABLE,
                sessions = sessions,
                chat = NoChat,
                images = FakeImages(),
                isTester = { tester },
                io = dispatcher,
            )
            backgroundScope.launch { model.reviewDue.collect { } }
            advanceUntilIdle()
            assertFalse("not an evaluator yet", model.reviewDue.value)

            tester = true
            // Any change to the conversation re-asks. A verdict landing is one.
            sessions.appendAssistantMessage("s1", "A verdict, in prose.")
            advanceUntilIdle()

            assertTrue("the questionnaire must open once the flag is known", model.reviewDue.value)
        }

    @Test
    fun `answering sends the review rather than waiting for the Reports screen`() = runTest {
        // The bug that shipped. The only flush points were opening Reports and deleting a
        // report, so the first real review ever given sat on the handset: the evaluator
        // answered, closed the app, and the server's table stayed empty with nothing
        // anywhere saying why.
        val sessions = FakeSessions(finished())
        var pushes = 0
        val model = model(sessions, isTester = true, pushReviews = { pushes++ })
        advanceUntilIdle()

        model.submitReview("no", null, 5, 9, null)
        advanceUntilIdle()

        assertEquals("answering must attempt the send itself", 1, pushes)
    }

    @Test
    fun `a review that cannot be sent is kept, not lost`() = runTest {
        // Offline in a workshop is the normal case here, not the exception — and the send
        // must not be able to take the app down with it either.
        val sessions = FakeSessions(finished())
        val model = model(sessions, isTester = true, pushReviews = { throw IOException("no signal") })
        advanceUntilIdle()

        model.submitReview("unsure", "Hard to tell from the photo", 3, 6, null)
        advanceUntilIdle()

        val kept = sessions.testerFeedback.single()
        assertEquals("unsure", kept.mistakes)
        assertFalse("the questionnaire still closes", model.reviewing.value)
    }

    // ---------------------------------------------------------------- harness

    private fun finished() = listOf(
        ChatMessage(UUID.randomUUID().toString(), Role.USER, "I am buying this."),
        ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, "A verdict, in prose."),
    )

    private fun model(
        sessions: SessionRepository,
        isTester: Boolean,
        pushReviews: suspend () -> Unit = {},
    ) = ChatViewModel(
        sessionId = "s1",
        declaredItemType = ItemType.WOODEN_TABLE,
        sessions = sessions,
        chat = NoChat,
        images = FakeImages(),
        isTester = { isTester },
        pushReviews = pushReviews,
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
