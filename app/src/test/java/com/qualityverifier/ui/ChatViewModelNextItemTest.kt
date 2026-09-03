package com.qualityverifier.ui

import android.net.Uri
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Usage
import com.qualityverifier.images.ImageQuality
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.chat.ChatViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * One assessment leading into the next, and the comparison between them.
 *
 * The case being served is somebody in a shop with several pieces in front of them. They
 * used to answer five intake questions per piece, four of whose answers had not changed
 * since the last one. Now the answers carry, the price is asked again because it is the
 * one thing that belongs to this piece, and the two finished assessments can be set side
 * by side.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelNextItemTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `the session row records the answers and the piece it followed`() = runTest {
        // Written to the row, not just held in memory: a report reopened tomorrow must
        // still be able to start the next piece without asking everything again.
        val sessions = FakeSessions()
        val vm = viewModel(
            sessions,
            FakeChat(ChatResult.Success("ok")),
            prefill = CARRIED,
            previousSessionId = "first",
        )
        dispatcher.scheduler.advanceUntilIdle()

        vm.submitIntake(CARRIED.copy(quotedPrice = "4000"), ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("first", sessions.createdFrom)
        assertEquals("4000", sessions.createdIntake?.quotedPrice)
        assertEquals(Usage.DAILY, sessions.createdIntake?.usage)
    }

    @Test
    fun `the answers offered to the next piece are this one's own`() = runTest {
        val sessions = FakeSessions()
        val vm = viewModel(sessions, FakeChat(ChatResult.Success("ok")))
        dispatcher.scheduler.advanceUntilIdle()
        assertNull(vm.carryForward.value)

        vm.submitIntake(CARRIED.copy(quotedPrice = "4000"), ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        val carried = vm.carryForward.value
        assertNotNull(carried)
        assertEquals(AssessmentLanguage.ENGLISH, carried?.language)
        assertEquals(AssessmentDepth.RAPID, carried?.depth)
    }

    @Test
    fun `an intake handed over to the assistant carries nothing forward`() = runTest {
        // Half an intake would silently answer questions nobody answered. The next piece
        // is better off being asked.
        val sessions = FakeSessions()
        val vm = viewModel(sessions, FakeChat(ChatResult.Success("ok")))
        dispatcher.scheduler.advanceUntilIdle()

        vm.submitIntake(
            AssessmentContext(language = AssessmentLanguage.ENGLISH, ownership = Ownership.BUYING),
            ReportLabels.ENGLISH,
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.carryForward.value)
        assertNull(sessions.createdIntake)
    }

    @Test
    fun `a comparison is offered once the earlier piece has a verdict`() = runTest {
        val sessions = FakeSessions(
            others = mapOf("first" to earlierAssessment(ItemType.WOODEN_TABLE)),
        )
        val vm = viewModel(
            sessions,
            FakeChat(ChatResult.Success("ok")),
            previousSessionId = "first",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.previousVerdict.value)
        assertEquals("Solid frame", vm.previousVerdict.value?.headline)
    }

    @Test
    fun `a different kind of piece is not offered as a comparison`() = runTest {
        // A chair against a table is two assessments, not a comparison, and asking for
        // one would produce a paragraph of invented differences.
        val sessions = FakeSessions(
            others = mapOf("first" to earlierAssessment(ItemType.WOODEN_CHAIR)),
        )
        val vm = viewModel(
            sessions,
            FakeChat(ChatResult.Success("ok")),
            previousSessionId = "first",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.previousVerdict.value)
    }

    @Test
    fun `an earlier assessment that never reached a verdict is not comparable`() = runTest {
        val sessions = FakeSessions(
            others = mapOf(
                "first" to Session(
                    start = SessionStart(ItemType.WOODEN_TABLE, null, null),
                    messages = listOf(
                        ChatMessage("m1", Role.ASSISTANT, "Take six photos please"),
                    ),
                ),
            ),
        )
        val vm = viewModel(
            sessions,
            FakeChat(ChatResult.Success("ok")),
            previousSessionId = "first",
        )
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(vm.previousVerdict.value)
    }

    @Test
    fun `the link back is read from the row when a conversation is reopened`() = runTest {
        // Nothing is passed in here: the app was restarted and this report was opened
        // from the list, so the row is the only thing that remembers.
        val sessions = FakeSessions(
            self = Session(
                start = SessionStart(ItemType.WOODEN_TABLE, "first", CARRIED),
                messages = listOf(ChatMessage("m1", Role.ASSISTANT, "Welcome")),
            ),
            others = mapOf("first" to earlierAssessment(ItemType.WOODEN_TABLE)),
        )
        val vm = viewModel(sessions, FakeChat(ChatResult.Success("ok")), declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertNotNull(vm.previousVerdict.value)
        assertEquals(CARRIED, vm.carryForward.value)
    }

    @Test
    fun `asking for a comparison sends the earlier findings as one turn`() = runTest {
        val sessions = FakeSessions(
            self = Session(
                start = SessionStart(ItemType.WOODEN_TABLE, "first", CARRIED),
                messages = listOf(ChatMessage("m1", Role.ASSISTANT, "Welcome")),
            ),
            others = mapOf("first" to earlierAssessment(ItemType.WOODEN_TABLE)),
        )
        val chat = FakeChat(ChatResult.Success("The first has squarer joints"))
        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        vm.compareWithPrevious(ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        val request = sessions.messages.first { it.role == Role.USER }.text
        assertTrue(request, request.contains("Solid frame"))
        assertTrue(request, request.contains("Gap at the rear leg"))
        assertTrue(request, request.contains("Whether the top is sealed"))
        // The earlier piece's photographs stay where they are: its findings are what
        // travel, and re-sending eight images would double the cost of this turn.
        assertTrue(sessions.messages.all { it.attachments.isEmpty() })

        // Asked once. Tapping the button again must not send the same wall of text twice.
        vm.compareWithPrevious(ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, chat.calls)
        assertTrue(vm.comparisonRequested.value)
    }

    @Test
    fun `nothing is sent when there is no earlier verdict to compare against`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("should not be called"))
        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        vm.compareWithPrevious(ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, chat.calls)
    }

    private fun viewModel(
        sessions: SessionRepository,
        chat: ChatService,
        declaredItemType: ItemType? = ItemType.WOODEN_TABLE,
        prefill: AssessmentContext? = null,
        previousSessionId: String? = null,
    ) = ChatViewModel(
        sessionId = "second",
        declaredItemType = declaredItemType,
        intakePrefill = prefill,
        declaredPreviousSessionId = previousSessionId,
        sessions = sessions,
        chat = chat,
        images = NoImages,
        io = dispatcher,
    )

    private fun earlierAssessment(itemType: ItemType) = Session(
        start = SessionStart(itemType, null, CARRIED),
        messages = listOf(
            ChatMessage("m1", Role.USER, "I am buying this."),
            ChatMessage("m2", Role.ASSISTANT, VERDICT_REPLY),
        ),
    )

    private data class Session(val start: SessionStart, val messages: List<ChatMessage>)

    private class FakeChat(vararg results: ChatResult) : ChatService {
        private val queue = results.toMutableList()
        var calls = 0
            private set

        override suspend fun send(
            sessionId: String,
            itemType: ItemType,
            history: List<ChatMessage>,
        ): ChatResult {
            calls++
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    /** Two or more conversations, because that is the whole subject of these tests. */
    private class FakeSessions(
        private val self: Session? = null,
        private val others: Map<String, Session> = emptyMap(),
    ) : SessionRepository {
        override suspend fun recordLocation(sessionId: String, fix: LocationFix) = Unit

        val messages = mutableListOf<ChatMessage>()
        var createdFrom: String? = null
            private set
        var createdIntake: AssessmentContext? = null
            private set

        private var selfStart: SessionStart? = self?.start

        private fun own() = self?.messages.orEmpty() + messages

        override fun observeSummaries(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
            MutableStateFlow(own())

        override suspend fun messagesOnce(sessionId: String) =
            others[sessionId]?.messages ?: own()

        override suspend fun startOf(sessionId: String) = others[sessionId]?.start ?: selfStart

        override suspend fun sessionExists(sessionId: String) = selfStart != null

        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
        ) {
            createdFrom = previousSessionId
            createdIntake = intake?.takeIf { it.isComplete }
            selfStart = SessionStart(itemType, previousSessionId, createdIntake)
        }

        override suspend fun appendUserMessage(
            sessionId: String,
            text: String,
            attachments: List<Attachment>,
        ): ChatMessage =
            ChatMessage(UUID.randomUUID().toString(), Role.USER, text, attachments)
                .also { messages += it }

        override suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage =
            ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, text)
                .also { messages += it }

        override suspend fun deleteMessage(messageId: String) {
            messages.removeAll { it.id == messageId }
        }

        override suspend fun deleteSession(sessionId: String) = Unit
        override suspend fun pruneOrphanImages() = Unit
        // Sync is not what these tests are about; the chat view model never calls these.
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

    private object NoImages : SessionImageStore {
        override fun newImageFile(sessionId: String) = File("/tmp/unused.jpg")
        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File) = false
        override fun delete(file: File) = Unit
        override fun measureQuality(file: File): ImageQuality? = null
    }

    private companion object {
        /** Rapid, so the intake completes without waiting on an opening photo. */
        val CARRIED = AssessmentContext(
            language = AssessmentLanguage.ENGLISH,
            ownership = Ownership.BUYING,
            usage = Usage.DAILY,
            depth = AssessmentDepth.RAPID,
        )

        val VERDICT_REPLY = """
            The frame is sound but one joint needs work.

            ```qv-verdict
            {
              "verdict": "fair",
              "language": "en",
              "headline": "Solid frame",
              "summary": "One joint to re-glue before daily use.",
              "defects": [
                {
                  "title": "Gap at the rear leg",
                  "area": "structural",
                  "severity": "moderate",
                  "what_i_see": "The stretcher is not seated the whole way into the leg.",
                  "what_it_means": "That joint will flex every time somebody leans on it.",
                  "what_to_do": "Open it out, re-glue and clamp."
                }
              ],
              "unverified": ["Whether the top is sealed. Ask the seller."]
            }
            ```
        """.trimIndent()
    }
}
