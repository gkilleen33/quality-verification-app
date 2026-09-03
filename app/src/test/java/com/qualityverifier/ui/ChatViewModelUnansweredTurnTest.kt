package com.qualityverifier.ui

import android.net.Uri
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.images.ImageQuality
import com.qualityverifier.ui.chat.ChatViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * Picking up a turn that never got a reply.
 *
 * The bug these cover: a failed send showed a Retry, but the error lived in the ViewModel,
 * so closing the app and reopening the assessment from Reports left the stored turn with no
 * way to finish it. Somebody who had taken nine photos and done three hands-on tests had to
 * start over.
 *
 * The state is now read from the conversation instead — a session whose last message is the
 * customer's is an unfinished turn — so it survives the app being killed, which until now
 * looked identical to a finished conversation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelUnansweredTurnTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `reopening a conversation whose last turn was the customer's offers to send again`() =
        runTest {
            // No error in memory: this is the reopen case, a fresh ViewModel over a
            // conversation that was left unfinished.
            val sessions = FakeSessions(
                existing = listOf(userTurn("I am buying this. Please answer in English.")),
                sessionExists = true,
            )
            val model = model(sessions, FakeChat())
            advanceUntilIdle()

            assertTrue(model.unansweredTurn.value)
            assertEquals("nothing may be sent without being asked", 0, chatCalls)
        }

    @Test
    fun `a conversation that ended with a reply offers nothing`() = runTest {
        val sessions = FakeSessions(
            existing = listOf(userTurn("I am buying this."), assistantTurn("Here is the plan.")),
            sessionExists = true,
        )
        val model = model(sessions, FakeChat())
        advanceUntilIdle()

        assertFalse(model.unansweredTurn.value)
    }

    @Test
    fun `an empty conversation offers nothing`() = runTest {
        // Opening a new assessment: the intake has not run, so there is no turn to finish.
        val model = model(FakeSessions(), FakeChat())
        advanceUntilIdle()

        assertFalse(model.unansweredTurn.value)
    }

    @Test
    fun `it stays quiet while a send is still in flight`() = runTest {
        // The important negative. During a normal send the customer's message is already
        // stored and the reply has not arrived, which is indistinguishable from the failure
        // this detects unless the in-flight flag is checked.
        val reply = CompletableDeferred<ChatResult>()
        val sessions = FakeSessions(sessionExists = true)
        val model = model(sessions, FakeChat(held = reply))

        model.send("I am buying this.")
        advanceUntilIdle()

        assertTrue("the turn should be stored by now", sessions.snapshot().isNotEmpty())
        assertEquals(Role.USER, sessions.snapshot().last().role)
        assertFalse("must not offer while the request is still out", model.unansweredTurn.value)

        // And once the reply lands there is nothing to finish.
        reply.complete(ChatResult.Success("Here is the plan."))
        advanceUntilIdle()
        assertFalse(model.unansweredTurn.value)
    }

    @Test
    fun `a send that fails offers to send again once it has finished failing`() = runTest {
        val sessions = FakeSessions(sessionExists = true)
        val model = model(sessions, FakeChat(ChatResult.Failure(com.qualityverifier.data.chat.ChatErrorKind.NETWORK, "No connection")))

        model.send("I am buying this.")
        advanceUntilIdle()

        assertTrue(model.unansweredTurn.value)
    }

    @Test
    fun `sending again re-delivers the stored turn without duplicating it`() = runTest {
        // Retry re-sends the conversation as it stands. A second copy of the customer's
        // message would be a second charge and a confusing transcript.
        val sessions = FakeSessions(
            existing = listOf(userTurn("I am buying this.")),
            sessionExists = true,
        )
        val chat = FakeChat(ChatResult.Success("Here is the plan."))
        val model = model(sessions, chat)
        advanceUntilIdle()
        assertTrue(model.unansweredTurn.value)

        model.retry()
        advanceUntilIdle()

        assertEquals("exactly one request", 1, chatCalls)
        assertEquals(
            "no second copy of the customer's turn",
            1,
            sessions.snapshot().count { it.role == Role.USER },
        )
        // And the offer goes away, because the conversation now ends with a reply.
        assertFalse(model.unansweredTurn.value)
        assertEquals(Role.ASSISTANT, sessions.snapshot().last().role)
    }

    // ---------------------------------------------------------------- harness

    private var chatCalls = 0

    private fun userTurn(text: String) =
        ChatMessage(UUID.randomUUID().toString(), Role.USER, text)

    private fun assistantTurn(text: String) =
        ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, text)

    /**
     * Builds the ViewModel and subscribes to [ChatViewModel.unansweredTurn].
     *
     * The subscription is not incidental. That flow is shared with
     * `SharingStarted.WhileSubscribed`, matching `messages`, so with nothing collecting it
     * never runs and holds its initial `false` — which is what these tests hit on the first
     * attempt. The screen collects it with `collectAsState`, so collecting here is the real
     * configuration rather than a workaround for it.
     */
    private fun TestScope.model(sessions: SessionRepository, chat: ChatService): ChatViewModel {
        val model = ChatViewModel(
            sessionId = "s1",
            declaredItemType = ItemType.WOODEN_TABLE,
            sessions = sessions,
            chat = chat,
            images = FakeImages(),
            io = dispatcher,
        )
        backgroundScope.launch { model.unansweredTurn.collect { } }
        return model
    }

    private inner class FakeChat(
        private val result: ChatResult = ChatResult.Success("a reply"),
        /** When set, the request blocks until completed, so in-flight state is observable. */
        private val held: CompletableDeferred<ChatResult>? = null,
    ) : ChatService {
        override suspend fun send(
            sessionId: String,
            itemType: ItemType,
            history: List<ChatMessage>,
        ): ChatResult {
            chatCalls++
            return held?.await() ?: result
        }
    }

    /**
     * Reactive, unlike the fake in the other chat tests: `observeMessages` there snapshots
     * at subscribe time, and a flag derived from the message list would never see an append.
     */
    private class FakeSessions(
        existing: List<ChatMessage> = emptyList(),
        private var sessionExists: Boolean = false,
    ) : SessionRepository {
        override suspend fun recordLocation(sessionId: String, fix: LocationFix) = Unit

        private val state = MutableStateFlow(existing)

        fun snapshot(): List<ChatMessage> = state.value

        override fun observeSummaries(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = state

        override suspend fun messagesOnce(sessionId: String) = state.value
        override suspend fun startOf(sessionId: String) =
            if (sessionExists) SessionStart(ItemType.WOODEN_TABLE, null, null) else null

        override suspend fun sessionExists(sessionId: String) = sessionExists

        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
        ) {
            sessionExists = true
        }

        override suspend fun appendUserMessage(
            sessionId: String,
            text: String,
            attachments: List<Attachment>,
        ): ChatMessage =
            ChatMessage(UUID.randomUUID().toString(), Role.USER, text, attachments)
                .also { message -> state.value = state.value + message }

        override suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage =
            ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, text)
                .also { message -> state.value = state.value + message }

        override suspend fun deleteMessage(messageId: String) {
            state.value = state.value.filterNot { it.id == messageId }
        }

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

        val testerFeedback = mutableListOf<com.qualityverifier.data.session.LocalTesterFeedback>()
        override suspend fun recordTesterFeedback(
            feedback: com.qualityverifier.data.session.LocalTesterFeedback,
        ) { testerFeedback += feedback }
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
