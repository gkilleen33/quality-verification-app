package com.qualityverifier.server

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.WorkerSlot
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.chat.ClaudeResult
import com.qualityverifier.server.chat.PhotoShelf
import com.qualityverifier.server.chat.TokenUsage
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.Digest
import com.qualityverifier.server.db.MessageRow
import com.qualityverifier.server.db.PieceStudio
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.SessionAccess
import com.qualityverifier.server.db.SessionRow
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.StoredReply
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.routes.SessionLocation
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant

/**
 * A manager assessing a linked worker's piece (v0.6.0, release scope row 11; Nathan,
 * 16 November 2026): Claude judges the piece afresh. Nothing from the worker's assessments
 * of the piece, neither their conversation nor their notes (V19's digests), may reach the
 * manager's request, though the two sessions share the piece.
 *
 * The store here keeps several sessions and their notes apart, as Postgres does, so a route
 * that read the piece's other sessions, or another session's notes, would put the worker's
 * words in front of Claude and fail this.
 */
class ManagerAssessmentTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun `the manager's request to Claude holds nothing from the worker's assessment of the same piece`() = testApplication {
        val store = SessionsByIdStore()
        // The worker's finished assessment of the piece, with its notes.
        store.messages[WORKER_SESSION] = mutableListOf(
            ChatMessage("w-1", Role.USER, "WORKER OPENING: the legs are mine"),
            ChatMessage("w-2", Role.ASSISTANT, "WORKER VERDICT: the back left joint has a gap"),
        )
        store.notes[WORKER_SESSION] = mutableListOf(Digest("w-2", "WORKER NOTES: gap at the back left joint", 3))
        store.pieceOf[WORKER_SESSION] = PIECE

        val claude = RecordingClaude()
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(Fundis, AccessTokens(KEY)),
                chat = Chat(store, BlobStore(folder.newFolder()), claude, FixedPrompts, NoFeedback),
            )
        }
        val client = createClient { install(ClientContentNegotiation) { json() } }

        // The manager's opening turn, from "Assess piece" on the worker's page.
        val response = client.post("/v1/fundi/chat") {
            header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(MANAGER).token}")
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"session_id":"$MANAGER_SESSION","item_type_id":"wooden-table","message_id":"m-1",
                 "text":"MANAGER OPENING","blobs":[],"piece_id":"$PIECE"}
                """.trimIndent(),
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(1, claude.calls)
        assertEquals("the piece reached the store", PIECE, store.pieceOf[MANAGER_SESSION])
        assertTrue(claude.sent, claude.sent.contains("MANAGER OPENING"))
        assertFalse(claude.sent, claude.sent.contains("WORKER"))
        assertEquals("the same prompt as any assessment", "PROMPT FOR wooden-table", claude.systemPrompt)
    }

    /** Sessions, messages and notes kept apart by session id, as the tables keep them. */
    private class SessionsByIdStore : ChatStore {
        val messages = mutableMapOf<String, MutableList<ChatMessage>>()
        val notes = mutableMapOf<String, MutableList<Digest>>()
        val pieceOf = mutableMapOf<String, String?>()

        override suspend fun ensureSession(
            sessionId: String, userId: String, itemTypeId: String,
            previousSessionId: String?, intakeAnswers: String?, promptSha: String?,
            audience: Audience, dailyLimit: Int, testerDailyLimit: Int,
            pieceId: String?, pieceLabel: String?, pieceStudio: PieceStudio, isFinal: Boolean,
            workers: List<WorkerSlot>, appVersionCode: Int?,
        ): SessionAccess {
            val created = sessionId !in messages
            messages.getOrPut(sessionId) { mutableListOf() }
            if (created) pieceOf[sessionId] = pieceId
            return SessionAccess.Ok(created)
        }

        override suspend fun appendUserTurn(
            sessionId: String, messageId: String, text: String, blobHashes: List<String>,
            composed: Boolean, photoMeta: Map<String, JsonObject>,
        ): Boolean {
            messages.getValue(sessionId) += ChatMessage(messageId, Role.USER, text)
            return true
        }

        override suspend fun history(sessionId: String, blobPath: (String) -> String): List<ChatMessage> =
            messages[sessionId].orEmpty().toList()

        override suspend fun digests(sessionId: String): List<Digest> = notes[sessionId].orEmpty().reversed()

        override suspend fun appendAssistantTurn(
            sessionId: String, text: String, preview: String, verdictLevelId: String?,
            verdictLanguage: String?, defectCount: ((List<String>) -> Int?)?,
        ): String {
            messages.getValue(sessionId) += ChatMessage("a-${messages.getValue(sessionId).size}", Role.ASSISTANT, text)
            return "a-1"
        }

        override suspend fun saveDigest(sessionId: String, digest: Digest) {
            notes.getOrPut(sessionId) { mutableListOf() } += digest
        }

        override suspend fun replaceSessionWorkers(userId: String, sessionId: String, workers: List<WorkerSlot>) = true
        override suspend fun recordSessionLocation(sessionId: String, userId: String, location: SessionLocation) = Unit
        override suspend fun replyAfter(sessionId: String, userMessageId: String): StoredReply? = null
        override suspend fun recordUsage(
            userId: String, sessionId: String?, model: String?, usage: TokenUsage?, httpStatus: Int?,
            latencyMillis: Long, errorKind: String?, audience: Audience, callKind: String,
        ) = Unit
        override suspend fun sessionsFor(userId: String): List<SessionRow> = emptyList()
        override suspend fun sessionDetail(userId: String, sessionId: String): Pair<SessionRow, List<MessageRow>>? = null
        override suspend fun markClientDeleted(userId: String, sessionId: String) = false
        override suspend fun blobBelongsTo(userId: String, sha: String) = false
    }

    /** Everything Claude was sent, flattened, so the test can assert an absence. */
    private class RecordingClaude : ClaudeClient {
        var calls = 0
        var sent = ""
        var systemPrompt = ""

        override suspend fun send(
            systemPrompt: String,
            history: List<ChatMessage>,
            imageBytes: (Attachment) -> ByteArray?,
            photos: PhotoShelf?,
        ): ClaudeResult {
            calls++
            this.systemPrompt = systemPrompt
            sent = history.joinToString("\n") { it.text }
            return ClaudeResult.Success("A reply with no verdict yet.", TokenUsage(1, 1, 0, 0), "m")
        }
    }

    private object FixedPrompts : PromptRepository {
        override suspend fun systemPromptFor(itemType: ItemType, audience: Audience) = "PROMPT FOR ${itemType.id}"
        override suspend fun compactionPrompt() = "WRITE YOUR NOTES"
        override suspend fun clearCache() = Unit
    }

    /** Every account is a Fundi Bora account. */
    private object Fundis : AuthStore {
        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = UserRow(
            userId, "Wanjirũ", "individual", null, disabled = false, isTester = false, audience = Audience.FUNDI,
        )
        override suspend fun issueRefresh(userId: String, token: String, expiresAt: Instant, userAgent: String?, replaces: String?) = "r"
        override suspend fun findRefresh(token: String): StoredRefresh? = null
        override suspend fun revokeChain(userId: String) = 0
        override suspend fun credentialsForPhone(phone: String): Credentials? = null
        override suspend fun recordFailedSignIn(userId: String, lockFor: Duration, threshold: Int) = 0
        override suspend fun clearFailedSignIns(userId: String) = Unit
        override suspend fun passwordHashFor(userId: String): String? = null
        override suspend fun setPasswordHash(userId: String, passwordHash: String) = Unit
        override suspend fun markAccountDeleted(userId: String) = Unit
    }

    private companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
        const val MANAGER = "33333333-2222-3333-4444-555555555555"
        const val PIECE = "c0ffee00-0000-4000-8000-00000000003a"
        const val WORKER_SESSION = "c0ffee00-0000-4000-8000-00000000003b"
        const val MANAGER_SESSION = "c0ffee00-0000-4000-8000-00000000003c"
    }
}
