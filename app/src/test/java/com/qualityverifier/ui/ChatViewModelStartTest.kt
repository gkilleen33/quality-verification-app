package com.qualityverifier.ui

import android.net.Uri
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.images.ImageQuality
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.AssessmentDepth
import com.qualityverifier.domain.AssessmentLanguage
import com.qualityverifier.domain.Ownership
import com.qualityverifier.domain.Usage
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.chat.CaptureTarget
import com.qualityverifier.ui.chat.ChatViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * How an assessment begins.
 *
 * There have been three answers to this. Originally the walkthrough started only once the
 * user typed something, because the API requires the first turn to be theirs. Then the
 * conversation opened itself, which fixed that but meant the first thing anybody saw was a
 * spinner, followed by three more round trips for a language, an ownership and a usage
 * question that needed no model at all.
 *
 * Now the app asks those itself and the customer's answers *are* the first turn. These
 * tests pin that down: opening sends nothing, and the intake sends exactly one request
 * carrying the context.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStartTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `opening a new conversation sends nothing and asks for the intake`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("should not be called"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        // The whole point: no network on open. The screen appears immediately.
        assertEquals(0, chat.calls)
        assertTrue(sessions.messages.isEmpty())
        assertTrue(vm.needsIntake.value)
    }

    @Test
    fun `a full assessment waits for its opening photo before sending`() = runTest {
        // The photo of the whole piece goes with the context, so the assistant can check
        // the item's protocol against the actual piece before planning seven shots of it.
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("here is the plan"))
        val images = FakeImages()

        val vm = viewModel(sessions, chat, images = images)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(SWAHILI_CONTEXT, ReportLabels.SWAHILI)
        dispatcher.scheduler.advanceUntilIdle()

        // Nothing sent yet: the camera is waiting.
        assertEquals(0, chat.calls)
        assertNotNull(vm.awaitingOpeningPhoto.value)

        vm.onPhotoCaptured(images.newImageFile("s1"), CaptureTarget.Opening)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        assertNull(vm.awaitingOpeningPhoto.value)
        val opening = sessions.messages.first()
        assertEquals(Role.USER, opening.role)
        assertEquals(1, opening.attachments.size)
    }

    @Test
    fun `a rapid assessment sends straight away, with no opening photo`() = runTest {
        // Rapid exists for speed and its plan is two wide photos anyway.
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("two photos please"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(ENGLISH_CONTEXT, ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        assertNull(vm.awaitingOpeningPhoto.value)
        assertTrue(sessions.messages.first().attachments.isEmpty())
    }

    @Test
    fun `backing out of the opening photo starts the conversation anyway`() = runTest {
        // Better than trapping somebody on a camera screen.
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("ok"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(SWAHILI_CONTEXT, ReportLabels.SWAHILI)
        dispatcher.scheduler.advanceUntilIdle()
        vm.skipOpeningPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        assertTrue(sessions.messages.first().attachments.isEmpty())
    }

    @Test
    fun `the intake sends one request whose first turn is the customer's own answers`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("Sawa. Full or rapid?"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        vm.submitIntake(SWAHILI_RAPID, ReportLabels.SWAHILI)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        assertEquals(false, vm.needsIntake.value)
        assertTrue(sessions.created)
        // The user turn comes first, so the service never has to synthesise an opener.
        assertEquals(listOf(Role.USER, Role.ASSISTANT), sessions.messages.map { it.role })
        val opening = sessions.messages.first().text
        assertTrue(opening, opening.contains("Ninanunua hii."))
        assertTrue(opening, opening.contains("Muuzaji anataka 3500."))
        assertTrue(opening, opening.contains("kila siku"))
        assertTrue(opening, opening.contains("Kiswahili"))
        assertTrue(opening, opening.contains("ukaguzi wa haraka"))
    }

    @Test
    fun `the history handed to the service already holds the context`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("ok"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(SWAHILI_RAPID, ReportLabels.SWAHILI)
        dispatcher.scheduler.advanceUntilIdle()

        val history = chat.histories.first()
        assertEquals(1, history.size)
        assertEquals(Role.USER, history.first().role)
    }

    @Test
    fun `reopening a conversation that already has turns skips the intake entirely`() = runTest {
        val sessions = FakeSessions(
            existing = listOf(ChatMessage("a1", Role.ASSISTANT, "Welcome")),
            sessionExists = true,
        )
        val chat = FakeChat(ChatResult.Success("should not be called"))

        val vm = viewModel(sessions, chat, declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, chat.calls)
        assertEquals(false, vm.needsIntake.value)
    }

    @Test
    fun `a session row with no messages still gets the intake`() = runTest {
        // A row with no messages is the wreckage of an assessment abandoned during the
        // intake. Keying on the session existing rather than on it being empty would
        // strand the user with a conversation they cannot start.
        val sessions = FakeSessions(existing = emptyList(), sessionExists = true)
        val chat = FakeChat(ChatResult.Success("Welcome, second time lucky"))

        val vm = viewModel(sessions, chat, declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(vm.needsIntake.value)
        assertEquals(0, chat.calls)
    }

    @Test
    fun `a failed opening surfaces an error and keeps the customer's turn for retry`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Failure(ChatErrorKind.NETWORK, "No internet connection"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(ENGLISH_CONTEXT, ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChatErrorKind.NETWORK, vm.error.value?.kind)
        // Their turn is stored, so retrying does not ask them the questions again.
        assertEquals(listOf(Role.USER), sessions.messages.map { it.role })
        assertEquals(false, vm.sending.value)
        assertEquals(false, vm.needsIntake.value)
    }

    @Test
    fun `retrying after a failed opening sends again without duplicating the turn`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(
            ChatResult.Failure(ChatErrorKind.NETWORK, "offline"),
            ChatResult.Success("Welcome"),
        )

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(ENGLISH_CONTEXT, ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, chat.calls)

        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, chat.calls)
        assertNull(vm.error.value)
        assertEquals(listOf(Role.USER, Role.ASSISTANT), sessions.messages.map { it.role })
    }

    @Test
    fun `an item type missing from the session row falls back rather than failing`() = runTest {
        val sessions = FakeSessions(itemType = null)
        val chat = FakeChat(ChatResult.Success("hello"))

        val vm = viewModel(sessions, chat, declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()
        vm.submitIntake(ENGLISH_CONTEXT, ReportLabels.ENGLISH)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(ItemType.OTHER), chat.itemTypes)
    }

    @Test
    fun `a photo that measures fine is attached without interrupting`() = runTest {
        val images = FakeImages(quality = null)
        val model = viewModel(FakeSessions(), FakeChat(ChatResult.Success("hi")), images = images)
        dispatcher.scheduler.advanceUntilIdle()

        model.onPhotoCaptured(images.newImageFile("s1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, model.pending.value.size)
        assertNull("a usable photo must not stop the shot list", model.review.value)
    }

    @Test
    fun `a blurred photo is held for the user to look at`() = runTest {
        val images = FakeImages(quality = ImageQuality(sharpness = 3.0, brightness = 140.0))
        val model = viewModel(FakeSessions(), FakeChat(ChatResult.Success("hi")), images = images)
        dispatcher.scheduler.advanceUntilIdle()

        model.onPhotoCaptured(images.newImageFile("s1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.pending.value.isEmpty())
        val review = model.review.value
        assertNotNull("a blurred photo should be held back", review)
        assertTrue(review!!.warning.contains("blurred"))

        // The user has looked at it and decided; their call wins over the measurement.
        model.keepReviewedPhoto()
        assertNull(model.review.value)
        assertEquals(1, model.pending.value.size)
    }

    @Test
    fun `retaking a held photo deletes it and attaches nothing`() = runTest {
        val images = FakeImages(quality = ImageQuality(sharpness = 3.0, brightness = 10.0))
        val model = viewModel(FakeSessions(), FakeChat(ChatResult.Success("hi")), images = images)
        dispatcher.scheduler.advanceUntilIdle()

        val file = images.newImageFile("s1")
        model.onPhotoCaptured(file)
        dispatcher.scheduler.advanceUntilIdle()
        assertNotNull("a dark, blurred photo should be held back", model.review.value)

        model.discardReviewedPhoto()
        dispatcher.scheduler.advanceUntilIdle()

        assertNull(model.review.value)
        assertTrue(model.pending.value.isEmpty())
        assertTrue("the discarded file should be cleaned up", images.deleted.contains(file))
    }

    @Test
    fun `a photo that cannot be decoded is dropped with an explanation`() = runTest {
        val images = FakeImages(normalises = false)
        val model = viewModel(FakeSessions(), FakeChat(ChatResult.Success("hi")), images = images)
        dispatcher.scheduler.advanceUntilIdle()

        model.onPhotoCaptured(images.newImageFile("s1"))
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue(model.pending.value.isEmpty())
        assertNull(model.review.value)
        assertFalse(images.deleted.isEmpty())
        assertTrue(model.notice.value.orEmpty().contains("could not be read"))
    }

    private fun viewModel(
        sessions: SessionRepository,
        chat: ChatService,
        declaredItemType: ItemType? = ItemType.WOODEN_TABLE,
        images: SessionImageStore = NoImages,
    ) = ChatViewModel(
        sessionId = "s1",
        declaredItemType = declaredItemType,
        sessions = sessions,
        chat = chat,
        images = images,
        io = dispatcher,
    )

    private class FakeChat(vararg results: ChatResult) : ChatService {
        private val queue = results.toMutableList()
        var calls = 0
            private set
        val histories = mutableListOf<List<ChatMessage>>()
        val itemTypes = mutableListOf<ItemType>()

        override suspend fun send(
            sessionId: String,
            itemType: ItemType,
            history: List<ChatMessage>,
        ): ChatResult {
            calls++
            histories += history
            itemTypes += itemType
            return if (queue.size > 1) queue.removeAt(0) else queue.first()
        }
    }

    private class FakeSessions(
        private val existing: List<ChatMessage> = emptyList(),
        private var sessionExists: Boolean = false,
        private val itemType: ItemType? = ItemType.WOODEN_TABLE,
    ) : SessionRepository {
        val messages = mutableListOf<ChatMessage>()
        var created = false
            private set

        private fun all() = existing + messages

        override fun observeSummaries(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
            MutableStateFlow(all())

        override suspend fun messagesOnce(sessionId: String) = all()
        override suspend fun startOf(sessionId: String) =
            if (sessionExists) SessionStart(itemType, null, null) else null

        override suspend fun sessionExists(sessionId: String) = sessionExists

        override suspend fun createSession(
            sessionId: String,
            itemType: ItemType,
            previousSessionId: String?,
            intake: AssessmentContext?,
        ) {
            created = true
            sessionExists = true
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

    }

    private companion object {
        /** Rapid, so it sends without waiting on an opening photo. */
        val SWAHILI_RAPID = AssessmentContext(
            language = AssessmentLanguage.SWAHILI,
            ownership = Ownership.BUYING,
            quotedPrice = "3500",
            usage = Usage.DAILY,
            depth = AssessmentDepth.RAPID,
        )

        val SWAHILI_CONTEXT = AssessmentContext(
            language = AssessmentLanguage.SWAHILI,
            ownership = Ownership.BUYING,
            quotedPrice = "3500",
            usage = Usage.DAILY,
            depth = AssessmentDepth.FULL,
        )

        val ENGLISH_CONTEXT = AssessmentContext(
            language = AssessmentLanguage.ENGLISH,
            ownership = Ownership.ALREADY_OWN,
            usage = Usage.OCCASIONAL,
            depth = AssessmentDepth.RAPID,
        )
    }

    private object NoImages : SessionImageStore {
        override fun newImageFile(sessionId: String) = File("/tmp/unused.jpg")
        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File) = false
        override fun delete(file: File) = Unit
        override fun measureQuality(file: File): ImageQuality? = null
    }

    /**
     * A store backed by a real temporary file, because the view model's capture path
     * checks that the file exists and has length before doing anything with it.
     */
    private class FakeImages(
        private val normalises: Boolean = true,
        private val quality: ImageQuality? = null,
    ) : SessionImageStore {
        val deleted = mutableListOf<File>()

        override fun newImageFile(sessionId: String): File =
            File.createTempFile("capture", ".jpg").apply {
                writeBytes(ByteArray(16))
                deleteOnExit()
            }

        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File) = normalises
        override fun delete(file: File) {
            deleted += file
            file.delete()
        }

        override fun measureQuality(file: File): ImageQuality? = quality
    }
}
