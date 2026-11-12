package com.qualityverifier.fundi.ui.workers

import com.qualityverifier.data.workers.WorkerNaming
import com.qualityverifier.domain.ChatMessage
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.Problem
import com.qualityverifier.domain.Role
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.problemsOf
import com.qualityverifier.text.writesConsequences

/**
 * What one worker's page shows: every piece the worker was named on, newest first, with
 * the parts they did and every assessment of that piece.
 *
 * Nothing here scores anybody. The problems are listed under the piece, as the
 * assessment found them on the piece (plan v7 §4.6; Nathan, 4 November 2026: no score);
 * which of them is the worker's is the manager's judgement, and the page draws no line
 * from a problem to a person. There is no count per worker either, for the same reason.
 *
 * Pure, so the rules can be tested without a database: the page's view model feeds it
 * the summaries, the worker's rows, and a reader for each assessment's replies.
 */
object WorkerPages {

    /** The assessments of [pieceId] the worker was named on, joined to what they did. */
    data class Piece(
        val pieceId: String,
        /** The piece's newest assessment, which is what "Open" opens and what names the piece. */
        val latest: SessionSummary,
        /**
         * The parts this worker is recorded on across the piece's assessments, in
         * [WorkPart] order; a null entry is the whole piece. Never empty: a worker named
         * on a piece is on some part of it or on the whole.
         */
        val parts: List<WorkPart?>,
        /** Every assessment of the piece, newest first, named on or not. */
        val assessments: List<Assessment>,
    )

    data class Assessment(
        val summary: SessionSummary,
        /** Why the manager opened it, from the intake code; null when not known. */
        val purpose: FundiPurpose?,
        /**
         * What it found, as [problemsIn] reads the stored replies. Empty when it found
         * nothing or gave no verdict or diagnosis; null when they cannot be known.
         */
        val problems: List<Problem>?,
    )

    /** What the page is narrowed to: one piece, and assessments started within the days. */
    data class Filter(
        val pieceId: String? = null,
        /** Epoch millis, inclusive, or null for no lower bound. */
        val from: Long? = null,
        /** Epoch millis, inclusive, or null for no upper bound. */
        val to: Long? = null,
    ) {
        val isEmpty: Boolean get() = pieceId == null && from == null && to == null
    }

    /**
     * Joins the worker's rows to the summaries. An assessment named in [namings] that is
     * no longer on the phone (deleted, or never synced here) is skipped rather than shown
     * as a piece with nothing in it.
     */
    suspend fun build(
        summaries: List<SessionSummary>,
        namings: List<WorkerNaming>,
        assessmentOf: suspend (SessionSummary) -> Assessment,
    ): List<Piece> {
        val byId = summaries.associateBy { it.id }
        val partsByPiece = linkedMapOf<String, MutableSet<WorkPart?>>()
        for (naming in namings) {
            val summary = byId[naming.sessionId] ?: continue
            partsByPiece.getOrPut(summary.piece) { linkedSetOf() } += naming.part
        }
        val byPiece = summaries.groupBy { it.piece }
        return partsByPiece.map { (pieceId, parts) ->
            val assessments = byPiece.getValue(pieceId).sortedByDescending { it.updatedAt }
            Piece(
                pieceId = pieceId,
                latest = assessments.first(),
                // The whole piece first, then the parts in the picker's order. Built with
                // an explicit null, which listOfNotNull would drop.
                parts = (if (null in parts) listOf<WorkPart?>(null) else emptyList()) +
                    WorkPart.entries.filter { it in parts },
                assessments = assessments.map { assessmentOf(it) },
            )
        }.sortedByDescending { it.latest.updatedAt }
    }

    /**
     * Narrows [pieces] to [filter]. The date range applies to when an assessment was
     * started; a piece with no assessment left in the range is dropped.
     */
    fun filter(pieces: List<Piece>, filter: Filter): List<Piece> =
        pieces.asSequence()
            .filter { filter.pieceId == null || it.pieceId == filter.pieceId }
            .map { piece ->
                piece.copy(
                    assessments = piece.assessments.filter { each ->
                        val at = each.summary.createdAt
                        (filter.from == null || at >= filter.from) && (filter.to == null || at <= filter.to)
                    },
                )
            }
            .filter { it.assessments.isNotEmpty() }
            .toList()

    /**
     * The problems one assessment found, from its stored replies, by the same rules as
     * the stored defect count (`fundiDefectCount` in Problems.kt), so the page never lists problems the
     * count says are gone. In order:
     *  1. the newest diagnosis is in the one-list shape: its findings;
     *  2. the newest verdict lists defects: those, with each finding's dimension;
     *  3. the newest diagnosis would not decode: null, since the problems are not known;
     *  4. the newest diagnosis is in the same reply as the newest verdict or a newer one,
     *     or there is no verdict (a diagnose-only check): its findings;
     *  5. otherwise none: a verdict-only follow-up with no defects cleared the diagnosis
     *     before it.
     */
    fun problemsIn(messages: List<ChatMessage>): List<Problem>? {
        val newestFirst = messages.filter { it.role == Role.ASSISTANT }
            .map { parseAssistantContent(it.text) }
            .asReversed()
        val verdictIndex = newestFirst.indexOfFirst { it.verdict != null }
        val diagnosisIndex = newestFirst.indexOfFirst { it.sentDiagnosis }
        val verdict = newestFirst.getOrNull(verdictIndex)?.verdict
        val diagnosisReply = newestFirst.getOrNull(diagnosisIndex)
        val diagnosis = diagnosisReply?.diagnosis
        return when {
            diagnosis != null && diagnosis.writesConsequences -> problemsOf(verdict, diagnosis)
            verdict != null && verdict.defects.isNotEmpty() -> problemsOf(verdict, diagnosis)
            diagnosisReply?.diagnosisUnreadable == true -> null
            diagnosis != null && (verdictIndex < 0 || diagnosisIndex <= verdictIndex) -> problemsOf(null, diagnosis)
            else -> emptyList()
        }
    }
}
