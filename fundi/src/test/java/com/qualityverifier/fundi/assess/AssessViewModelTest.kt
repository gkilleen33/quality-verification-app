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
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.PriorIssue
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.AssessmentLanguage
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
import org.junit.Assert.assertFalse
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
        pieceId: String = "s1",
        returning: Boolean = false,
    ) = AssessViewModel(
        sessionId = "s1",
        itemType = ItemType.WOODEN_TABLE,
        pieceId = pieceId,
        returning = returning,
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

        model(sessions = sessions, profiles = profiles).start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE), "/tmp/whole.jpg")
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
            .start(FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE), "/tmp/whole.jpg")
        advanceUntilIdle()

        assertEquals(1, sessions.userTurns.size)
        // Not empty: the language line goes out even with no profile behind it. Coaching
        // without the tool list is worse advice; coaching in a language the maker cannot
        // read is none at all.
        assertTrue(
            sessions.userTurns.single().text,
            sessions.userTurns.single().text.trimEnd().endsWith("Please answer me in English."),
        )
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

    // ------------------------------------------------------------------ the intake

    // The rule Kagua keeps and this one must: nothing reaches the model until the intake
    // is whole. A diagnose with no description is half a question.
    @Test
    fun `an incomplete intake sends nothing`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.DIAGNOSE),
            "/tmp/problem.jpg",
        )
        advanceUntilIdle()

        assertTrue(sessions.userTurns.isEmpty())
        assertTrue(sessions.created.isEmpty())
    }

    @Test
    fun `the opening turn leads with the purpose and is marked as the app's own`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.SWAHILI, FundiPurpose.DIAGNOSE, details = "loose leg"),
            "/tmp/problem.jpg",
        )
        advanceUntilIdle()

        val opening = sessions.userTurns.single()
        assertTrue(opening.text, opening.text.startsWith("I want to diagnose and fix"))
        assertTrue(opening.text, opening.text.trimEnd().endsWith("Tafadhali nijibu kwa Kiswahili."))
        // Shown compactly, not word for word — the first build printed its own prompt.
        assertTrue("the opening turn is the app's, not the maker's", opening.composed)
    }

    // A first assessment names its own piece; the stored code is what reopening reads.
    @Test
    fun `a new assessment is its own piece, and the intake is stored`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.EVALUATE),
            "/tmp/whole.jpg",
        )
        advanceUntilIdle()

        val created = sessions.created.single()
        assertEquals("s1", created.pieceId)
        assertEquals("fundi-en-evaluate", created.intakeCode)
    }

    // Learning a skill may come before anything has been cut.
    @Test
    fun `an intake with no photo still sends`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).start(
            FundiIntake(AssessmentLanguage.ENGLISH, FundiPurpose.LEARN, details = "dovetails"),
            photoPath = null,
        )
        advanceUntilIdle()

        assertEquals(1, sessions.userTurns.size)
        assertTrue(sessions.userTurns.single().attachments.isEmpty())
    }

    // A re-assessment is a new session on the same piece. Its findings to check come from
    // that piece's earlier assessments — and only that piece's.
    @Test
    fun `checking a fix offers the piece's own earlier findings, and nobody else's`() = runTest {
        val diagnosis = """
            ```fb-diagnosis
            {"findings": [{"title": "Gapping joint", "what_happened": "3mm at the back"}]}
            ```
        """.trimIndent()
        val otherPiece = """
            ```fb-diagnosis
            {"findings": [{"title": "Cupped top"}]}
            ```
        """.trimIndent()
        val sessions = FakeSessions(
            earlier = listOf(
                summary(id = "first", piece = "stool") to listOf(diagnosis),
                summary(id = "elsewhere", piece = "table") to listOf(otherPiece),
            ),
        )

        val model = model(sessions = sessions, pieceId = "stool", returning = true)
        advanceUntilIdle()

        assertEquals(
            listOf(PriorIssue("Gapping joint", "3mm at the back")),
            model.priorIssues.value,
        )
    }

    // An assessment made before pieces existed has no piece id, and counts as its own.
    @Test
    fun `an old assessment with no piece id is its own piece`() = runTest {
        val legacy = """
            ```fb-diagnosis
            {"findings": [{"title": "Rough edge"}]}
            ```
        """.trimIndent()
        val sessions = FakeSessions(
            earlier = listOf(summary(id = "legacy", piece = null) to listOf(legacy)),
        )

        val model = model(sessions = sessions, pieceId = "legacy", returning = true)
        advanceUntilIdle()

        assertEquals(listOf("Rough edge"), model.priorIssues.value.map { it.title })
    }

    @Test
    fun `a plan submission is marked as the app's own`() = runTest {
        val sessions = FakeSessions()
        val model = model(sessions = sessions)

        model.offerPlanFrom(ChatMessage("a1", Role.ASSISTANT, plannedReply))
        model.attachShot(0, "/tmp/shot0.jpg")
        model.submitRun()
        advanceUntilIdle()

        assertTrue(sessions.userTurns.single().composed)
    }

    @Test
    fun `a reply the maker types is not`() = runTest {
        val sessions = FakeSessions()

        model(sessions = sessions).send("It was cut freehand")
        advanceUntilIdle()

        assertFalse(sessions.userTurns.single().composed)
    }

    private fun summary(id: String, piece: String?) = SessionSummary(
        id = id,
        itemType = ItemType.WOODEN_STOOL,
        createdAt = 0L,
        updatedAt = 0L,
        preview = "",
        messageCount = 2,
        pieceId = piece,
    )

    // ------------------------------------------------------------------ fakes

    private class Turn(
        val text: String,
        val attachments: List<Attachment>,
        val composed: Boolean,
    )

    private class Created(val sessionId: String, val pieceId: String?, val intakeCode: String?)

    /**
     * Only the handful of methods an assessment touches. The rest of SessionRepository
     * is sync and housekeeping, and a fake that pretended to implement them would invite
     * a test to lean on behaviour nothing here has.
     *
     * [earlier] seeds other assessments on the handset, for the re-assessment tests:
     * each is a summary and the assistant turns its conversation holds.
     */
    private class FakeSessions(
        private val earlier: List<Pair<SessionSummary, List<String>>> = emptyList(),
    ) : SessionRepository {
        val userTurns = mutableListOf<Turn>()
        val created = mutableListOf<Created>()
        private val stream = MutableStateFlow<List<ChatMessage>>(emptyList())

        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> = stream
        override suspend fun messagesOnce(sessionId: String): List<ChatMessage> =
            earlier.firstOrNull { it.first.id == sessionId }?.second
                ?.mapIndexed { i, text -> ChatMessage("$sessionId-$i", Role.ASSISTANT, text) }
                ?: stream.value
        override fun observeSummaries(): Flow<List<SessionSummary>> =
            MutableStateFlow(earlier.map { it.first })
        override suspend fun startOf(sessionId: String): SessionStart? = null
        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
            pieceId: String?,
            intakeCode: String?,
        ) {
            created += Created(sessionId, pieceId, intakeCode)
        }
        override suspend fun sessionExists(sessionId: String) = false
        override suspend fun appendUserMessage(
            sessionId: String,
            text: String,
            attachments: List<Attachment>,
            composed: Boolean,
        ): ChatMessage {
            userTurns += Turn(text, attachments, composed)
            val message = ChatMessage(
                "u${userTurns.size}", Role.USER, text, attachments, composed = composed,
            )
            stream.value = stream.value + message
            return message
        }
        override suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage {
            val message = ChatMessage("a${stream.value.size}", Role.ASSISTANT, text)
            stream.value = stream.value + message
            return message
        }

        private fun no(): Nothing = error("an assessment does not touch this")

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
