package com.qualityverifier.fundi.assess

import android.net.Uri
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.fundi.FundiProfiles
import com.qualityverifier.data.fundi.ProfileOutcome
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.session.SyncedMessage
import com.qualityverifier.data.session.SyncedSession
import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import com.qualityverifier.fundi.ui.assess.AssessViewModel
import com.qualityverifier.images.ImageQuality
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The parts of an assessment that are decisions rather than layout.
 *
 * Two of them are worth the words. A plan must not be offered twice, because the second
 * offer asks a maker to retake photographs already attached to a sent turn. And the
 * maker's tool list has to reach the opening turn, because a fix built around clamps
 * nobody owns is the failure the coaching prompt names outright.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AssessViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val plannedReply = """
        Here is what I need to see.
        ```qv-plan
        {"summary":"Six shots","photos":[{"title":"Whole piece","instruction":"Stand back"}],
         "tests":[]}
        ```
    """.trimIndent()

    private fun model(
        chat: ChatService = FakeChat(),
        sessions: FakeSessions = FakeSessions(),
        profiles: FundiProfiles = FakeProfiles(),
    ) = AssessViewModel(
        sessionId = "s1",
        itemType = ItemType.WOODEN_TABLE,
        sessions = sessions,
        chat = chat,
        images = FakeImages(),
        profiles = profiles,
        io = dispatcher,
    )

    // The tool list is the whole argument for collecting a profile. If it does not reach
    // the opening turn, the coaching is guessing and issue #41 cannot be answered.
    @Test
    fun `the maker's tools travel with the opening photo`() = runTest {
        val sessions = FakeSessions()
        val profiles = FakeProfiles(
            ProfileOutcome.Loaded(
                FundiProfile(
                    workshop = Workshop(makes = "stools"),
                    tools = listOf(
                        OwnedTool(ToolKind.CHISELS, ToolOwnership.OWNED),
                        OwnedTool(ToolKind.CLAMPS, ToolOwnership.NONE),
                    ),
                )
            )
        )

        model(sessions = sessions, profiles = profiles).start("/tmp/whole.jpg")
        advanceUntilIdle()

        val opening = sessions.userTurns.single()
        assertTrue(opening.text, opening.text.contains("chisels"))
        // Named as absent, not merely left out. Only a stated "I have none" licenses the
        // fix plan to work around it.
        assertTrue(opening.text, opening.text.contains("Tools I do not have: clamps"))
        assertEquals(1, opening.attachments.size)
    }

    // Offline at the worst moment. Refusing to look at the piece would be the wrong
    // trade: tool-blind coaching is worse advice, not no advice.
    @Test
    fun `a profile that will not load does not block the assessment`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions, profiles = FakeProfiles(ProfileOutcome.Unavailable))
            .start("/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(1, sessions.userTurns.size)
        // Not empty: the language line goes out even with no profile behind it. Coaching
        // without the tool list is worse advice; coaching in a language the maker cannot
        // read is none at all.
        assertEquals("Please answer me in English.", sessions.userTurns.single().text)
    }

    @Test
    fun `a plan in the reply becomes a run`() = runTest {
        val model = model()

        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))

        assertNotNull(model.run.value)
        assertEquals("a1", model.run.value?.sourceMessageId)
    }

    @Test
    fun `the maker's own turn is never a plan`() = runTest {
        val model = model()

        model.offerPlanFrom(ChatMessage("u1", Role.USER, plannedReply))

        assertNull(model.run.value)
    }

    // The rule that matters. Once a run is sent, its plan must never be offered again —
    // the photographs are already attached to a stored turn, and re-offering asks the
    // maker to take them a second time.
    @Test
    fun `a plan already answered is not offered again`() = runTest {
        val model = model()
        val reply = ChatMessage("a1", Role.ASSISTANT, plannedReply)

        model.offerPlanFrom(reply)
        model.attachShot(0, "/tmp/shot0.jpg")
        model.submitRun()
        advanceUntilIdle()
        assertNull("the run is cleared once sent", model.run.value)

        model.offerPlanFrom(reply)

        assertNull("and must not come back", model.run.value)
    }

    // Marked answered before the request rather than after, so a send that fails does
    // not re-offer a plan whose photographs are already on a stored turn.
    @Test
    fun `a failed send still does not re-offer the plan`() = runTest {
        val model = model(chat = FakeChat(ChatResult.Failure(ChatErrorKind.NETWORK, "offline")))
        val reply = ChatMessage("a1", Role.ASSISTANT, plannedReply)

        model.offerPlanFrom(reply)
        model.submitRun()
        advanceUntilIdle()

        assertNotNull("the maker is told", model.error.value)
        model.offerPlanFrom(reply)
        assertNull(model.run.value)
    }

    // Skipping is an answer. A shot recorded as absent would let the model assume it
    // passed, which is the one failure the whole run flow exists to avoid.
    @Test
    fun `a skipped shot is recorded, not dropped`() = runTest {
        val model = model()
        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))

        model.skipShot(0)

        val run = model.run.value!!
        assertTrue("the key is present", run.shots.containsKey(0))
        assertNull("with no photo behind it", run.shots[0])
        assertTrue("so the run counts as complete", run.photosDone)
    }

    @Test
    fun `an empty turn is never sent`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).send("   ")
        advanceUntilIdle()

        assertTrue(sessions.userTurns.isEmpty())
    }

    // ------------------------------------------------------------------ fakes

    private class Turn(val text: String, val attachments: List<Attachment>)

    /**
     * Only the handful of methods an assessment touches. The rest of SessionRepository
     * is sync and housekeeping, and a fake that pretended to implement them would invite
     * a test to lean on behaviour nothing here has.
     */
    private class FakeSessions : SessionRepository {
        val userTurns = mutableListOf<Turn>()
        private val stream = MutableStateFlow<List<ChatMessage>>(emptyList())

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = stream
        override suspend fun messagesOnce(sessionId: String) = stream.value
        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
        ) = Unit
        override suspend fun sessionExists(sessionId: String) = false
        override suspend fun appendUserMessage(
            sessionId: String,
            text: String,
            attachments: List<Attachment>,
        ): ChatMessage {
            userTurns += Turn(text, attachments)
            val message = ChatMessage("u${userTurns.size}", Role.USER, text, attachments)
            stream.value = stream.value + message
            return message
        }
        override suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage {
            val message = ChatMessage("a${stream.value.size}", Role.ASSISTANT, text)
            stream.value = stream.value + message
            return message
        }

        private fun no(): Nothing = error("an assessment does not touch this")

        override fun observeSummaries() = no()
        override suspend fun startOf(sessionId: String) = no()
        override suspend fun recordLocation(sessionId: String, fix: LocationFix) = no()
        override suspend fun deleteMessage(messageId: String) = no()
        override suspend fun deleteSession(sessionId: String) = no()
        override suspend fun pruneOrphanImages() = no()
        override suspend fun knownSessions() = no()
        override suspend fun writeSynced(
            session: SyncedSession,
            messages: List<SyncedMessage>,
        ) = no()
        override suspend fun pendingRemoteDeletes() = no()
        override suspend fun recordPendingRemoteDelete(sessionId: String) = no()
        override suspend fun clearPendingRemoteDelete(sessionId: String) = no()
        override suspend fun recordTesterFeedback(feedback: LocalTesterFeedback) = no()
        override suspend fun pendingTesterFeedback() = no()
        override suspend fun hasPendingTesterFeedback(sessionId: String) = no()
        override suspend fun clearTesterFeedback(sessionId: String) = no()
        override suspend fun dismissLocally(sessionId: String) = no()
        override suspend fun dismissedSessions() = no()
    }

    private class FakeChat(
        private val result: ChatResult = ChatResult.Success("ok"),
    ) : ChatService {
        override suspend fun send(
            sessionId: String,
            itemType: ItemType,
            history: List<ChatMessage>,
        ) = result
    }

    private class FakeProfiles(
        private val outcome: ProfileOutcome = ProfileOutcome.NotSetUpYet,
    ) : FundiProfiles {
        override suspend fun load() = outcome
        override suspend fun save(profile: FundiProfile) = true
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
