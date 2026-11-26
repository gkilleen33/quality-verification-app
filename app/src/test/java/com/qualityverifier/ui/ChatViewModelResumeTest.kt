package com.qualityverifier.ui

import android.net.Uri
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.RunProgress
import com.qualityverifier.data.session.RunProgressStore
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
import com.qualityverifier.ui.chat.CaptureTarget
import com.qualityverifier.ui.chat.ChatViewModel
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * An assessment left before it was sent (v0.7.1, release scope row 57). My reports shows it as
 * "NOT FINISHED · Tap to carry on"; carrying on must find the photos already taken and the
 * answers already given, not ask for all of them again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelResumeTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `row 57 - an unfinished check reopens with its photos and answers kept`() = runTest {
        val sessions = FakeSessions(listOf(userTurn("My answers."), plan))
        val progress = MemoryProgress()

        // The first visit: one photo taken, one test answered, then the customer leaves.
        val first = model(sessions, progress)
        advanceUntilIdle()
        val photo = File.createTempFile("shot", ".jpg").apply { writeText("jpeg"); deleteOnExit() }
        first.onPhotoCaptured(photo, CaptureTarget.PlanShot(0))
        first.answerTest(0, "Solid")
        advanceUntilIdle()

        // Reopened from My reports: a new view model over the same conversation.
        val second = model(sessions, progress)
        advanceUntilIdle()
        val run = second.run.value!!
        assertEquals(photo.absolutePath, run.shots[0])
        assertEquals("Solid", run.answers[0])
        assertEquals("the next photo asked for is the second", 1, run.nextShot)
        assertNull(run.nextTest)
    }

    @Test
    fun `row 57 - a photo whose file has gone is asked for again`() {
        val run = com.qualityverifier.domain.PlanRun(
            com.qualityverifier.text.parseAssistantContent(plan.text).plan!!,
            plan.id,
        )
        val saved = RunProgress(plan.id, shots = mapOf(0 to "/gone.jpg", 1 to null), answers = mapOf(0 to "Wobbles"))
        val restored = saved.restore(run, exists = { false })
        assertEquals("a skipped shot stays skipped", mapOf(1 to null), restored.shots)
        assertEquals(0, restored.nextShot)
        // Progress kept for another plan is not put onto this one.
        assertTrue(RunProgress("another reply", shots = mapOf(0 to "/x.jpg")).restore(run).shots.isEmpty())
    }

    @Test
    fun `row 57 - sending the run clears what was kept`() = runTest {
        val sessions = FakeSessions(listOf(userTurn("My answers."), plan))
        val progress = MemoryProgress()
        val model = model(sessions, progress)
        advanceUntilIdle()
        model.skipShot(0)
        model.skipShot(1)
        model.answerTest(0, "Solid")
        advanceUntilIdle()
        assertTrue(progress.saved.containsKey("s1"))
        model.submitRun(com.qualityverifier.text.ReportLabels.ENGLISH)
        advanceUntilIdle()
        assertTrue(progress.saved.isEmpty())
    }

    private val plan = ChatMessage(
        "plan-1",
        Role.ASSISTANT,
        """
        Two photos and one test.

        ```qv-plan
        {"photos":[{"title":"Whole piece"},{"title":"Under the top"}],"tests":[{"title":"Rock it","options":[{"label":"Solid"},{"label":"Wobbles"}]}]}
        ```
        """.trimIndent(),
    )

    private fun userTurn(text: String) = ChatMessage(UUID.randomUUID().toString(), Role.USER, text)

    private fun TestScope.model(sessions: SessionRepository, progress: RunProgressStore): ChatViewModel {
        val model = ChatViewModel(
            sessionId = "s1",
            declaredItemType = ItemType.WOODEN_TABLE,
            sessions = sessions,
            chat = object : ChatService {
                override suspend fun send(sessionId: String, itemType: ItemType, history: List<ChatMessage>) =
                    ChatResult.Success("A reply.")
            },
            images = FakeImages(),
            io = dispatcher,
            progress = progress,
        )
        backgroundScope.launch { model.messages.collect { } }
        return model
    }

    private class MemoryProgress : RunProgressStore {
        val saved = mutableMapOf<String, RunProgress>()
        override suspend fun load(sessionId: String) = saved[sessionId]
        override suspend fun save(sessionId: String, progress: RunProgress) { saved[sessionId] = progress }
        override suspend fun clear(sessionId: String) { saved.remove(sessionId) }
    }

    private class FakeSessions(existing: List<ChatMessage>) : SessionRepository {
        private val state = MutableStateFlow(existing)
        override suspend fun recordLocation(sessionId: String, fix: LocationFix) = Unit
        override fun observeSummaries(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = state
        override suspend fun messagesOnce(sessionId: String) = state.value
        override suspend fun startOf(sessionId: String) = SessionStart(ItemType.WOODEN_TABLE, null, null)
        override suspend fun sessionExists(sessionId: String) = true
        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
            pieceId: String?,
            intakeCode: String?,
            pieceLabel: String?,
            pieceStudio: com.qualityverifier.data.session.PieceStudioChoice,
            pieceMine: Boolean,
            isFinal: Boolean,
        ) = Unit
        override suspend fun appendUserMessage(sessionId: String, text: String, attachments: List<Attachment>, composed: Boolean) =
            ChatMessage(UUID.randomUUID().toString(), Role.USER, text, attachments).also { state.value = state.value + it }
        override suspend fun appendAssistantMessage(sessionId: String, text: String) =
            ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, text).also { state.value = state.value + it }
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
        override suspend fun dismissLocally(sessionId: String) = Unit
        override suspend fun dismissedSessions(): Set<String> = emptySet()
        override suspend fun pieceLabel(pieceId: String): String? = null
        override suspend fun setPieceLabel(pieceId: String, label: String?) = Unit
        override suspend fun unsyncedPieceLabels() = emptyList<com.qualityverifier.data.session.PieceLabelChange>()
        override suspend fun markPieceLabelSynced(change: com.qualityverifier.data.session.PieceLabelChange) = Unit
        override suspend fun recordTesterFeedback(feedback: com.qualityverifier.data.session.LocalTesterFeedback) = Unit
        override suspend fun pendingTesterFeedback() = emptyList<com.qualityverifier.data.session.LocalTesterFeedback>()
        override suspend fun hasPendingTesterFeedback(sessionId: String) = false
        override suspend fun clearTesterFeedback(sessionId: String) = Unit
    }

    private class FakeImages : SessionImageStore {
        override fun newImageFile(sessionId: String): File = File.createTempFile("capture", ".jpg").apply { deleteOnExit() }
        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File) = true
        override fun delete(file: File) { file.delete() }
        override fun measureQuality(file: File): ImageQuality? = null
    }
}
