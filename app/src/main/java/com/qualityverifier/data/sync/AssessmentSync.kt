package com.qualityverifier.data.sync

import android.util.Log
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.session.SyncedMessage
import com.qualityverifier.data.session.SyncedSession
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Role
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Brings the phone's copy of the assessments up to date with the server's.
 *
 * Until this existed the server wrote and never read: it held every assessment while the
 * phone could not see any of them, so reinstalling the app or moving to a new handset lost
 * a customer's whole history with a perfectly good copy sitting on disk.
 *
 * Deliberately one-directional. Assessments are created on the phone and pushed by the
 * chat client as they happen, so the only thing missing was pulling down what this
 * particular handset has never seen. A two-way merge would need conflict rules for
 * conversations that cannot conflict.
 */
class AssessmentSync(
    private val client: SyncClient,
    private val sessions: SessionRepository,
    private val images: SessionImageStore,
    /** Holds the cached evaluator flag, refreshed on every run. */
    private val tokens: TokenStore,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    data class Result(
        val fetched: Int,
        val deletesFlushed: Int,
        val reachedServer: Boolean,
        /** Evaluator reviews that reached the server, or were refused as unusable. */
        val reviews: Int = 0,
    )

    /**
     * Pushes what the phone owes, then fetches anything it does not have.
     *
     * Deletions go first on purpose: otherwise a sync could re-download the very
     * assessment the customer just deleted, because the server has not been told yet.
     * Evaluator reviews go up in the same pass and for the same reason — they are unsent
     * local work, and a failing pull should not strand them for another cycle.
     */
    suspend fun run(): Result = withContext(io) {
        var deletes = 0
        for (id in sessions.pendingRemoteDeletes()) {
            if (client.deleteSession(id)) {
                sessions.clearPendingRemoteDelete(id)
                deletes++
            }
        }

        // Reviews go up before anything comes down, same as deletes: they are the phone's
        // own unsent work, and a pull failing should not strand them for another cycle.
        var reviews = 0
        for (feedback in sessions.pendingTesterFeedback()) {
            if (client.submitTesterFeedback(feedback)) {
                sessions.clearTesterFeedback(feedback.sessionId)
                reviews++
            }
        }

        // Refreshed here so a promotion in the portal reaches the phone without a
        // re-install. Null means the server was unreachable, in which case the cached
        // answer is better than assuming either way.
        client.isTester()?.let(tokens::setTester)

        val remote = client.sessions()
            ?: return@withContext Result(0, deletes, reachedServer = false, reviews = reviews)

        val known = sessions.knownSessions()
        var fetched = 0
        for (summary in remote) {
            val localStamp = known[summary.id]
            // Skip anything the phone already has at the same age or newer. The common
            // case is a phone that created the assessment itself, so most of a list will
            // be skipped without a second request.
            if (localStamp != null && localStamp >= summary.updatedAt) continue

            val detail = client.session(summary.id) ?: continue
            val itemType = ItemType.fromId(detail.session.itemTypeId)
            if (itemType == null) {
                // A protocol this build does not know about — an older app against a newer
                // server. Skipped rather than guessed, so it reappears after an update
                // instead of being stored as the wrong kind of thing.
                Log.w(TAG, "Skipping ${summary.id}: unknown item type ${detail.session.itemTypeId}")
                continue
            }

            val messages = detail.messages.map { message ->
                SyncedMessage(
                    id = message.id,
                    role = if (message.role == "USER") Role.USER else Role.ASSISTANT,
                    text = message.text,
                    ordinal = message.ordinal,
                    createdAt = message.createdAt,
                    attachmentPaths = message.blobs.mapNotNull { fetchPhoto(summary.id, it) },
                )
            }

            sessions.writeSynced(
                SyncedSession(
                    id = detail.session.id,
                    itemType = itemType,
                    createdAt = detail.session.createdAt,
                    updatedAt = detail.session.updatedAt,
                    preview = detail.session.preview,
                    verdictLevelId = detail.session.verdictLevelId,
                    verdictLanguage = detail.session.verdictLanguage,
                    previousSessionId = detail.session.previousSessionId,
                    intakeAnswers = detail.session.intakeAnswers,
                ),
                messages,
            )
            fetched++
        }
        Result(fetched, deletes, reachedServer = true, reviews = reviews)
    }

    /**
     * Writes a photo to local storage, returning its path.
     *
     * A photo that will not download is skipped rather than failing the assessment: a
     * conversation with eight of nine photos is worth having, and the text of the verdict
     * is the part somebody acts on.
     */
    private suspend fun fetchPhoto(sessionId: String, sha256: String): String? {
        val bytes = client.blob(sha256)
        if (bytes == null) {
            Log.w(TAG, "Could not fetch a photo for $sessionId")
            return null
        }
        val file = runCatching { images.newImageFile(sessionId) }.getOrNull() ?: return null
        return runCatching {
            file.writeBytes(bytes)
            file.absolutePath
        }.getOrNull()
    }

    private companion object {
        const val TAG = "AssessmentSync"
    }
}
