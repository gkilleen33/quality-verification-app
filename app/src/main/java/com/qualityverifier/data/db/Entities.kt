package com.qualityverifier.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `serverId` and `updatedAt` are unused in Phase 1 but present from the first schema
 * version so that Phase 2 sync needs no migration.
 */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val itemTypeId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val previewText: String,
    val serverId: String? = null,
    /**
     * The last verdict level this assessment reached, so the reports list can show a
     * badge without loading and re-parsing every conversation. Null until a verdict
     * arrives, which is the normal state for an assessment still in progress.
     */
    val verdictLevelId: String? = null,
    /**
     * Language of that verdict, so the reports list can badge it in the same language
     * the assessment was written in. Null for a verdict stored before this was recorded.
     */
    val verdictLanguage: String? = null,
    /**
     * The assessment this one was started from, when the customer tapped "check another"
     * at the end of it. Null for an assessment started from the grid, which is most.
     *
     * Deliberately not a foreign key. Deleting the earlier report must not cascade into
     * this one, and a dangling id is a state the app already handles: no earlier verdict
     * to read means the comparison is simply not offered.
     */
    val previousSessionId: String? = null,
    /**
     * This assessment's own intake answers, in the form
     * [com.qualityverifier.text.encodeIntake] writes, so that "check another" can carry
     * them into the next piece even after the conversation has been reopened from
     * history. Null when the intake was handed over to the assistant part way, since
     * there is then no complete set to carry.
     */
    val intakeAnswers: String? = null,
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("sessionId")],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val text: String,
    val ordinal: Int,
    val createdAt: Long,
    val serverId: String? = null,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["messageId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("messageId")],
)
data class AttachmentEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    /** Path relative to the app's files directory, e.g. `images/<sessionId>/<uuid>.jpg`. */
    val relativePath: String,
    val mimeType: String,
)

/**
 * A deletion the server has not been told about yet.
 *
 * The session row goes the moment somebody taps delete — anything else would leave a
 * report on screen that the customer believes is gone. So the intent to tell the server
 * outlives the row it refers to, and a failed or offline delete is retried later. Without
 * this the server keeps its copy indefinitely and the seven-day window we tell customers
 * about would be untrue for exactly the deletions that happened out of signal.
 */
@Entity(tableName = "pending_remote_deletes")
data class PendingRemoteDeleteEntity(
    @PrimaryKey val sessionId: String,
    val requestedAt: Long,
)

/**
 * An evaluator's review, waiting to reach the server.
 *
 * Written locally first and pushed on the next sync, for the same reason as
 * [PendingRemoteDeleteEntity]: an evaluator finishes an assessment in a workshop, which is
 * exactly where there is no signal. Losing the review would mean the walkthrough happened
 * and the measurement did not, and it cannot be reconstructed afterwards — nobody
 * remembers, three days later, whether the assistant confused a dowel with a tenon.
 *
 * Keyed on the session, so answering twice corrects the first answer rather than queuing a
 * second one.
 */
@Entity(tableName = "pending_tester_feedback")
data class PendingTesterFeedbackEntity(
    @PrimaryKey val sessionId: String,
    /** "yes" | "no" | "unsure" */
    val mistakes: String,
    val mistakesDetail: String?,
    val adviceStars: Int,
    val itemQuality: Int,
    val extraFeedback: String?,
    val recordedAt: Long,
)
