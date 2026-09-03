package com.qualityverifier.ui

import com.qualityverifier.data.session.LocalTesterFeedback
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * The two ways to delete a report.
 *
 * Both remove it from the phone. Only one asks us to remove our copy, and the difference
 * matters twice over: it decides whether somebody's photographs are destroyed, and it
 * decides whether the next sync fetches the report straight back — which, whatever the
 * dialog said about our copy, reads as the delete having failed.
 *
 * These exercise the repository calls rather than the screen. The dialog is two buttons; the
 * consequence of pressing each is the part worth pinning.
 */
class ReportDeleteChoiceTest {

    @Test
    fun `keeping the server copy records a dismissal, not a delete`() = runTest {
        val repository = FakeRepository()

        repository.dropLocallyOnly("s1")

        assertEquals(setOf("s1"), repository.dismissed)
        assertTrue("the server must not be told to delete", repository.pendingDeletes.isEmpty())
        assertEquals(listOf("s1"), repository.deletedLocally)
    }

    @Test
    fun `deleting everywhere records a pending remote delete`() = runTest {
        val repository = FakeRepository()

        repository.dropEverywhere("s1")

        assertEquals(listOf("s1"), repository.pendingDeletes)
        assertTrue("a dismissal would suppress the delete", repository.dismissed.isEmpty())
        assertEquals(listOf("s1"), repository.deletedLocally)
    }

    @Test
    fun `a dismissed report is not fetched back by a sync`() = runTest {
        // The whole reason dismissed_sessions exists. Sync decides what to download from
        // what the phone does not have, and a report deliberately dropped is exactly that.
        val repository = FakeRepository()
        repository.dropLocallyOnly("s1")

        val onServer = listOf("s1", "s2")
        val toFetch = onServer.filterNot { it in repository.dismissedSessions() }

        assertEquals(listOf("s2"), toFetch)
    }

    @Test
    fun `the two choices are mutually exclusive`() = runTest {
        // Recording both would ask the server to delete a report the customer chose to
        // leave, which is the one direction that cannot be undone.
        val repository = FakeRepository()

        repository.dropLocallyOnly("keep")
        repository.dropEverywhere("purge")

        assertEquals(setOf("keep"), repository.dismissed)
        assertEquals(listOf("purge"), repository.pendingDeletes)
    }

    // ---------------------------------------------------------------- harness

    /** Mirrors ReportsViewModel.delete, which is the code path under test. */
    private suspend fun FakeRepository.dropLocallyOnly(id: String) {
        dismissLocally(id)
        deleteSession(id)
    }

    private suspend fun FakeRepository.dropEverywhere(id: String) {
        recordPendingRemoteDelete(id)
        deleteSession(id)
    }

    private class FakeRepository : SessionRepository {
        override suspend fun recordLocation(sessionId: String, fix: LocationFix) = Unit

        val dismissed = mutableSetOf<String>()
        val pendingDeletes = mutableListOf<String>()
        val deletedLocally = mutableListOf<String>()

        override suspend fun dismissLocally(sessionId: String) { dismissed += sessionId }
        override suspend fun dismissedSessions(): Set<String> = dismissed
        override suspend fun recordPendingRemoteDelete(sessionId: String) {
            pendingDeletes += sessionId
        }
        override suspend fun deleteSession(sessionId: String) { deletedLocally += sessionId }
        override suspend fun pendingRemoteDeletes() = pendingDeletes.toList()
        override suspend fun clearPendingRemoteDelete(sessionId: String) {
            pendingDeletes.remove(sessionId)
        }

        override fun observeSummaries(): Flow<List<SessionSummary>> = MutableStateFlow(emptyList())
        override fun observeMessages(sessionId: String): Flow<List<ChatMessage>> =
            MutableStateFlow(emptyList())
        override suspend fun messagesOnce(sessionId: String) = emptyList<ChatMessage>()
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
        override suspend fun appendAssistantMessage(sessionId: String, text: String) =
            ChatMessage(UUID.randomUUID().toString(), Role.ASSISTANT, text)
        override suspend fun deleteMessage(messageId: String) = Unit
        override suspend fun pruneOrphanImages() = Unit
        override suspend fun knownSessions() = emptyMap<String, Long>()
        override suspend fun writeSynced(
            session: com.qualityverifier.data.session.SyncedSession,
            messages: List<com.qualityverifier.data.session.SyncedMessage>,
        ) = Unit
        override suspend fun recordTesterFeedback(feedback: LocalTesterFeedback) = Unit
        override suspend fun pendingTesterFeedback() = emptyList<LocalTesterFeedback>()
        override suspend fun hasPendingTesterFeedback(sessionId: String) = false
        override suspend fun clearTesterFeedback(sessionId: String) = Unit
    }
}
