package com.qualityverifier.data.session

import com.qualityverifier.domain.AssessmentContext
import com.qualityverifier.domain.Attachment
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LocationFix
import com.qualityverifier.domain.SessionStart
import com.qualityverifier.domain.SessionSummary
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Conversation history.
 *
 * Phase 1 is Room-only. Phase 2 keeps this interface and adds server sync behind it,
 * so the home and chat screens are unaffected.
 */
interface SessionRepository {
    fun observeSummaries(): Flow<List<SessionSummary>>

    fun observeMessages(sessionId: String): Flow<List<ChatMessage>>

    suspend fun messagesOnce(sessionId: String): List<ChatMessage>

    /**
     * A piece's stage as the server stored it (V44, row 55), kept only when newer than the
     * phone's. A default no-op for fakes that have no pieces.
     */
    suspend fun applyPieceStage(pieceId: String, stage: String, setAt: Long) {}

    /**
     * What an existing assessment was started with. Null when there is no row yet, which
     * is the normal state until the first send.
     */
    suspend fun startOf(sessionId: String): SessionStart?

    /**
     * Records where the assessment was made. A no-op once one is already recorded.
     *
     * Separate from [createSession] because the fix arrives minutes later — it resolves
     * while the customer answers the intake — and nothing waits for it.
     */
    suspend fun recordLocation(sessionId: String, fix: LocationFix)

    /**
     * Creates the session row. Called on the first send, not on item selection.
     *
     * [previousSessionId] and [intake] are what make one assessment lead into the next:
     * the link back is what the comparison reads, and the answers are what the next
     * piece's intake carries forward instead of asking again.
     */
    suspend fun createSession(
        sessionId: String,
        itemType: ItemType,
        previousSessionId: String? = null,
        intake: AssessmentContext? = null,
        /** Fundi Bora's piece. Null for Kagua, which assesses each piece once. */
        pieceId: String? = null,
        /**
         * An intake code to store as-is, for an app whose intake is not Kagua's. Wins
         * over [intake] when both are given; nothing passes both.
         */
        intakeCode: String? = null,
        /**
         * What the maker calls the piece, from the intake. Written to the piece when
         * this is its first assessment, so the opening turn carries it; recorded as a
         * rename when the piece already has a different name.
         */
        pieceLabel: String? = null,
        /**
         * The studio a new piece is for (Fundi Bora v0.6.0): [PieceStudioChoice.NotAsked]
         * for a returning piece, whose label is the piece's own and changes only through
         * [setPieceStudio].
         */
        pieceStudio: PieceStudioChoice = PieceStudioChoice.NotAsked,
        /**
         * False when the piece is a linked worker's and this account assesses it as the
         * manager of the studio it is labelled for (row 11): the piece row is kept with the
         * worker's name, and nothing about it is sent as this account's change.
         */
        pieceMine: Boolean = true,
        /** The final assessment of the finished piece, before a customer report (row 27). */
        isFinal: Boolean = false,
    )

    suspend fun sessionExists(sessionId: String): Boolean

    suspend fun appendUserMessage(
        sessionId: String,
        text: String,
        attachments: List<Attachment>,
        /** Written by the app on the user's behalf. See [ChatMessage.composed]. */
        composed: Boolean = false,
    ): ChatMessage

    suspend fun appendAssistantMessage(sessionId: String, text: String): ChatMessage

    /** Removes a message — used to roll back a failed turn the user chose not to retry. */
    suspend fun deleteMessage(messageId: String)

    suspend fun deleteSession(sessionId: String)

    /** Housekeeping: drop image files belonging to sessions that were never saved. */
    suspend fun pruneOrphanImages()

    /** Ids already stored, so a sync only fetches what is missing. */
    suspend fun knownSessions(): Map<String, Long>

    /**
     * Writes an assessment fetched from the server, replacing any local copy.
     *
     * Ids come from the server, which got them from a phone, so this is idempotent: the
     * same assessment synced twice produces one row.
     */
    suspend fun writeSynced(session: SyncedSession, messages: List<SyncedMessage>)

    /**
     * Deletions the server has not been told about yet.
     *
     * Kept because the local row is gone the moment somebody taps delete, and without a
     * record the server would keep its copy indefinitely — making the seven days we
     * promise a customer untrue whenever the delete happened offline.
     */
    suspend fun pendingRemoteDeletes(): List<String>

    suspend fun recordPendingRemoteDelete(sessionId: String)

    suspend fun clearPendingRemoteDelete(sessionId: String)

    /**
     * Stores an evaluator's review of one assessment, to be sent on the next sync.
     *
     * Local first, because an evaluator finishes an assessment in a workshop and that is
     * exactly where there is no signal. A review that failed to send and was lost would
     * mean the walkthrough happened and the measurement did not, and it cannot be
     * reconstructed later.
     */
    suspend fun recordTesterFeedback(feedback: LocalTesterFeedback)

    suspend fun pendingTesterFeedback(): List<LocalTesterFeedback>

    /** Whether this assessment already has a review waiting to be sent. */
    suspend fun hasPendingTesterFeedback(sessionId: String): Boolean

    suspend fun clearTesterFeedback(sessionId: String)

    /**
     * Records that this phone dropped a report but left the server's copy alone.
     *
     * Kept so the next pull does not fetch it back. A report that reappears after being
     * deleted reads as the delete having failed, whatever we told them about our copy.
     */
    suspend fun dismissLocally(sessionId: String)

    suspend fun dismissedSessions(): Set<String>

    /** What the maker calls a piece, or null. Fundi Bora only. */
    suspend fun pieceLabel(pieceId: String): String?

    /**
     * Whether one of this account's pieces is labelled for a studio (V34). False for "My own
     * job" and for a piece never asked. Decides whether its maker may make the final
     * assessment (row 27). False in fakes and in Kagua, which has no pieces.
     */
    suspend fun pieceIsForStudio(pieceId: String): Boolean = false

    /**
     * The "Studios I work for" entry one of this account's pieces is labelled for (V34), or
     * null for "My own job" and a piece never asked. With the entry's linked mark, decides
     * whether the maker makes the final assessment (row 51). Null in fakes and in Kagua.
     */
    suspend fun pieceStudioId(pieceId: String): String? = null

    /**
     * Renames a piece, or clears its name with null. Written locally first and sent on
     * the next sync, before the pull, so a name given offline is not overwritten.
     */
    suspend fun setPieceLabel(pieceId: String, label: String?)

    /** Names the server has not been told yet. */
    suspend fun unsyncedPieceLabels(): List<PieceLabelChange>

    suspend fun markPieceLabelSynced(change: PieceLabelChange)

    /**
     * Labels one of this account's pieces with a studio, or "My own job" with null: the
     * first answer for a piece never asked, or its one correction (V34). Written locally
     * first and sent on the next sync.
     *
     * The three label methods have defaults that do nothing, as Kagua has no pieces and its
     * test doubles need not grow them; the Room repository overrides all three.
     */
    suspend fun setPieceStudio(pieceId: String, studioId: String?) = Unit

    /** Label changes the server has not been told yet. */
    suspend fun unsyncedPieceStudios(): List<PieceStudioChange> = emptyList()

    /**
     * Pieces with a name or a studio change the server has not had yet, as they change
     * (v0.6.0, row 6): the pieces list tags each "Not sent yet". Empty in fakes.
     */
    fun observeUnsentPieceIds(): Flow<Set<String>> = flowOf(emptySet())

    /**
     * The server's number for each synced photo of [sessionId], by local path (v0.6.0, row
     * 19). A photo the sync could not download is absent, and the numbers of the rest stand,
     * so a defect Claude names by number opens the right photo. Photos taken on this phone
     * have no entry and are counted in order. Empty in fakes.
     */
    suspend fun photoNumbers(sessionId: String): Map<String, Int> = emptyMap()

    /** The change reached the server, or the server refused it for good; either way it is not resent. */
    suspend fun markPieceStudioSent(change: PieceStudioChange) = Unit
}

/** A rename waiting to reach the server. [asOf] is the phone's clock at the rename. */
data class PieceLabelChange(val pieceId: String, val label: String?, val asOf: Long?)

/** A studio label waiting to reach the server. [studioId] null is "My own job". */
data class PieceStudioChange(val pieceId: String, val studioId: String?, val asOf: Long)

/** The answer to "Which studio is this for?" when a new piece is named. */
sealed interface PieceStudioChoice {
    /** A returning piece, or an account with no studios on its list. */
    data object NotAsked : PieceStudioChoice
    data object OwnJob : PieceStudioChoice
    data class Studio(val studioId: String) : PieceStudioChoice
}

/** An evaluator's review as the phone holds it, before it reaches the server. */
data class LocalTesterFeedback(
    val sessionId: String,
    /** "yes" | "no" | "unsure" */
    val mistakes: String,
    val mistakesDetail: String?,
    val adviceStars: Int,
    val itemQuality: Int,
    val extraFeedback: String?,
)
