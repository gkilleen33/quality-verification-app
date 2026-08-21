package com.qualityverifier.ui

import android.net.Uri
import com.qualityverifier.data.chat.ChatErrorKind
import com.qualityverifier.data.chat.ChatResult
import com.qualityverifier.data.chat.ChatService
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionSummary
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.UUID

/**
 * The walkthrough used to begin only once the user typed something, because the API
 * requires the first turn to be theirs. These cover the fix: the conversation opens
 * itself, exactly once, and only when there is nothing in it yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelStartTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a new conversation opens itself without the user sending anything`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("Welcome — send a photo of the whole table."))

        viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        assertEquals(listOf(ItemType.WOODEN_TABLE), chat.itemTypes)
        // The assistant's greeting is the only stored turn; nothing was faked as the user.
        assertEquals(listOf(Role.ASSISTANT), sessions.messages.map { it.role })
        assertTrue(sessions.created)
    }

    @Test
    fun `the assistant is handed an empty history so the service supplies the opener`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Success("hello"))

        viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        assertTrue("history should be empty: ${chat.histories.first()}", chat.histories.first().isEmpty())
    }

    @Test
    fun `reopening a conversation that already has turns does not send anything`() = runTest {
        val sessions = FakeSessions(
            existing = listOf(ChatMessage("a1", Role.ASSISTANT, "Welcome")),
            sessionExists = true,
        )
        val chat = FakeChat(ChatResult.Success("should not be called"))

        viewModel(sessions, chat, declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(0, chat.calls)
    }

    @Test
    fun `a session whose opening failed tries again when reopened`() = runTest {
        // A row with no messages is the wreckage of a failed opening request. Keying on
        // the session existing rather than on it being empty would strand the user.
        val sessions = FakeSessions(existing = emptyList(), sessionExists = true)
        val chat = FakeChat(ChatResult.Success("Welcome, second time lucky"))

        viewModel(sessions, chat, declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, chat.calls)
        assertEquals(listOf(Role.ASSISTANT), sessions.messages.map { it.role })
    }

    @Test
    fun `a failed opening surfaces an error and stores nothing`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(ChatResult.Failure(ChatErrorKind.NETWORK, "No internet connection"))

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(ChatErrorKind.NETWORK, vm.error.value?.kind)
        assertTrue(sessions.messages.isEmpty())
        assertEquals(false, vm.sending.value)
    }

    @Test
    fun `retrying after a failed opening sends again and stores the greeting`() = runTest {
        val sessions = FakeSessions()
        val chat = FakeChat(
            ChatResult.Failure(ChatErrorKind.NETWORK, "offline"),
            ChatResult.Success("Welcome"),
        )

        val vm = viewModel(sessions, chat)
        dispatcher.scheduler.advanceUntilIdle()
        assertEquals(1, chat.calls)

        vm.retry()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(2, chat.calls)
        assertNull(vm.error.value)
        assertEquals(listOf(Role.ASSISTANT), sessions.messages.map { it.role })
    }

    @Test
    fun `an item type missing from the session row falls back rather than failing`() = runTest {
        val sessions = FakeSessions(itemType = null)
        val chat = FakeChat(ChatResult.Success("hello"))

        viewModel(sessions, chat, declaredItemType = null)
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(listOf(ItemType.OTHER), chat.itemTypes)
    }

    // ---- fakes ----

    private fun viewModel(
        sessions: SessionRepository,
        chat: ChatService,
        declaredItemType: ItemType? = ItemType.WOODEN_TABLE,
    ) = ChatViewModel(
        sessionId = "s1",
        declaredItemType = declaredItemType,
        sessions = sessions,
        chat = chat,
        images = NoImages,
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
        override suspend fun itemTypeOf(sessionId: String) = itemType
        override suspend fun sessionExists(sessionId: String) = sessionExists

        override suspend fun createSession(sessionId: String, itemType: ItemType) {
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
    }

    private object NoImages : SessionImageStore {
        override fun newImageFile(sessionId: String) = File("/tmp/unused.jpg")
        override fun importFromUri(sessionId: String, uri: Uri): File? = null
        override fun normaliseInPlace(file: File) = false
        override fun delete(file: File) = Unit
    }
}
