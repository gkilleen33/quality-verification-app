package com.qualityverifier.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What `GET /v1/fundi/workers/{id}/pieces` answers (v0.6.0, release scope rows 4, 11 and 22):
 * the pieces a report of one linked worker's work can cover, for the manager's page on that
 * worker.
 *
 * Two kinds of piece. The manager's own pieces that name the worker in "Who worked on this?"
 * (as since #88), and the worker's own pieces labelled for the manager's studio ([theirs]),
 * which the manager can assess from the page. Each carries only the assessments the manager
 * may read: the manager's own, and on the worker's pieces the worker's too. Never another
 * account's, and never a score.
 *
 * Shared by the server, which builds it, and the phone, which shows it, as [ReportPreview]
 * is, so the two cannot drift.
 */
@Serializable
data class WorkerPieces(val pieces: List<WorkerPiece>)

@Serializable
data class WorkerPiece(
    val id: String,
    @SerialName("item_type_id") val itemTypeId: String,
    /** The maker's name for the piece; null when unnamed. */
    val label: String? = null,
    /** The worker made this piece and labelled it for the manager's studio: "Assess piece" applies. */
    val theirs: Boolean = false,
    /** Oldest first. */
    val assessments: List<WorkerPieceAssessment> = emptyList(),
)

@Serializable
data class WorkerPieceAssessment(
    @SerialName("session_id") val sessionId: String,
    /** Epoch millis. */
    @SerialName("created_at") val createdAt: Long,
    /** Made by the worker on their own phone; false for the manager's own. */
    @SerialName("by_worker") val byWorker: Boolean,
    /** The newest verdict's level, or null when the assessment gave none. */
    @SerialName("verdict_level_id") val verdictLevelId: String? = null,
    /** The final assessment of the finished piece (row 27). */
    val final: Boolean = false,
    /** False when the problems cannot be known: the newest diagnosis would not decode. */
    @SerialName("problems_known") val problemsKnown: Boolean = true,
    val problems: List<WorkerPieceProblem> = emptyList(),
)

/** One problem as the page lists it: its title and severity, nothing else. */
@Serializable
data class WorkerPieceProblem(
    val title: String,
    /** A [Severity] id, or blank. */
    val severity: String = "",
)
