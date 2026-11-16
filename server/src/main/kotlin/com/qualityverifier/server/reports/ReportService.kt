package com.qualityverifier.server.reports

import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.WorkerPiece
import com.qualityverifier.domain.WorkerPieceAssessment
import com.qualityverifier.domain.WorkerPieceProblem
import com.qualityverifier.domain.WorkerPieces
import com.qualityverifier.server.db.ReportPiece
import com.qualityverifier.server.db.ReportRow
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.db.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** What a report is about: a piece (every kind but one), or one linked roster worker. */
sealed interface ReportTarget {
    data class Piece(val pieceId: String) : ReportTarget
    data class Worker(val workerId: String) : ReportTarget
}

/**
 * Issues a report: reads the piece or the worker, composes it, serves it from the cache or
 * renders it, and writes its row (plan v7 §5.4).
 *
 * One render at a time across the server, a semaphore of one: a render decodes photos of
 * up to 20 MB each, and two makers tapping at once must not double that on the heap. A
 * second request waits up to [waitForRender] and then answers busy, which the phone shows
 * as "try again"; its retry a few seconds later finds the first render in the cache if it
 * was for the same report.
 */
class ReportService(
    private val store: ReportStore,
    private val photos: PhotoSource,
    private val cache: ReportCache,
    private val renderer: PdfRenderer,
    private val composer: ReportComposer = ReportComposer(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val waitForRender: Long = 30_000L,
) {
    private val log = LoggerFactory.getLogger(ReportService::class.java)
    private val oneRender = Semaphore(1)

    sealed interface Outcome {
        data class Issued(val bytes: ByteArray, val reportId: String, val sha256: String) : Outcome
        data object NoSuchPiece : Outcome
        /** A `session_id` that is not a listed assessment of this piece. */
        data object NoSuchSession : Outcome
        data object NoSuchWorker : Outcome
        /**
         * The link the kind needs does not hold: no linked studio on the piece for "My
         * manager", an unlinked worker, or a customer report asked for by a worker on a
         * piece that is the studio's to report on.
         */
        data class NotLinked(val code: String) : Outcome
        /** A customer report before the final assessment of the finished piece (row 27). */
        data object FinalRequired : Outcome
        data class Invalid(val code: String, val detail: String? = null) : Outcome
        data object Busy : Outcome
    }

    sealed interface PreviewOutcome {
        data class Ready(val preview: ReportPreview) : PreviewOutcome
        data object NoSuchPiece : PreviewOutcome
        data object NoSuchSession : PreviewOutcome
        data object NoSuchWorker : PreviewOutcome
        data class NotLinked(val code: String) : PreviewOutcome
        data object FinalRequired : PreviewOutcome
        data class Invalid(val code: String, val detail: String? = null) : PreviewOutcome
    }

    sealed interface WorkerPiecesOutcome {
        data class Ready(val pieces: WorkerPieces) : WorkerPiecesOutcome
        data object NoSuchWorker : WorkerPiecesOutcome
        data object NotLinked : WorkerPiecesOutcome
    }

    suspend fun preview(userId: String, target: ReportTarget, request: ReportRequest): PreviewOutcome =
        when (val prepared = prepare(userId, target, request)) {
            is Prepared.Ready -> PreviewOutcome.Ready(prepared.content.preview())
            Prepared.NoSuchPiece -> PreviewOutcome.NoSuchPiece
            Prepared.NoSuchSession -> PreviewOutcome.NoSuchSession
            Prepared.NoSuchWorker -> PreviewOutcome.NoSuchWorker
            is Prepared.NotLinked -> PreviewOutcome.NotLinked(prepared.code)
            Prepared.FinalRequired -> PreviewOutcome.FinalRequired
            is Prepared.Invalid -> PreviewOutcome.Invalid(prepared.code, prepared.detail)
        }

    /**
     * The pieces of one linked roster worker that a worker-work report can cover, with the
     * assessments the manager may read (rows 4, 11 and 22): the manager's own pieces that
     * name the worker, and the worker's own pieces labelled for the manager's studio. Each
     * piece once. No score: the date, the verdict's level, and the problems' titles.
     */
    suspend fun workerPieces(userId: String, workerId: String): WorkerPiecesOutcome {
        if (!store.ownsWorker(userId, workerId)) return WorkerPiecesOutcome.NoSuchWorker
        val worker = store.linkedWorker(userId, workerId) ?: return WorkerPiecesOutcome.NotLinked
        val pieces = coveredPieces(userId, workerId, worker.accountId).map { (piece, assessments) ->
            WorkerPiece(
                id = piece.id,
                itemTypeId = piece.itemTypeId,
                label = piece.label,
                theirs = piece.userId == worker.accountId,
                assessments = assessments.filter { it.listed }.map { assessment ->
                    WorkerPieceAssessment(
                        sessionId = assessment.sessionId,
                        createdAt = assessment.createdAt,
                        byWorker = assessment.userId == worker.accountId,
                        verdictLevelId = assessment.verdict?.level?.id,
                        final = assessment.isFinal,
                        problemsKnown = !assessment.problems.unknown,
                        problems = assessment.problems.problems.map { WorkerPieceProblem(it.title, it.severity) },
                    )
                },
            )
        }
        return WorkerPiecesOutcome.Ready(WorkerPieces(pieces))
    }

    suspend fun issue(userId: String, target: ReportTarget, request: ReportRequest): Outcome {
        val content = when (val prepared = prepare(userId, target, request)) {
            is Prepared.Ready -> prepared.content
            Prepared.NoSuchPiece -> return Outcome.NoSuchPiece
            Prepared.NoSuchSession -> return Outcome.NoSuchSession
            Prepared.NoSuchWorker -> return Outcome.NoSuchWorker
            is Prepared.NotLinked -> return Outcome.NotLinked(prepared.code)
            Prepared.FinalRequired -> return Outcome.FinalRequired
            is Prepared.Invalid -> return Outcome.Invalid(prepared.code, prepared.detail)
        }
        val key = cacheKey(content, target, request)
        val bytes = cache.get(key) ?: run {
            val acquired = withTimeoutOrNull(waitForRender) { oneRender.acquire() }
            if (acquired == null) {
                log.warn("Render refused as busy after {} ms", waitForRender)
                return Outcome.Busy
            }
            try {
                // Checked again under the semaphore: the request that waited may be a retry
                // of the one that was rendering.
                cache.get(key) ?: withContext(Dispatchers.Default) {
                    val started = clock()
                    val rendered = renderer.renderWithinCap(content, photos)
                    log.info(
                        "Rendered a {} report: {} bytes, {} photos at {} px, {} ms",
                        content.kind.id, rendered.bytes.size, rendered.photosPrinted,
                        rendered.settings.longEdge, clock() - started,
                    )
                    rendered.bytes
                }.also { cache.put(key, it) }
            } finally {
                oneRender.release()
            }
        }
        // Every response writes its own row, cached or rendered, so the rows count
        // downloads started and a retry has the same sha256 as the first attempt.
        val sha = ReportCache.sha256(bytes)
        val id = store.record(
            ReportRow(
                pieceId = (target as? ReportTarget.Piece)?.pieceId,
                userId = userId,
                kind = content.kind.id,
                sessionIds = content.sessionIds,
                defectRefs = content.defectRefs,
                defectSnapshot = json.encodeToString(ReportSnapshot.serializer(), content.snapshot),
                parts = content.parts.map { it.id }.takeIf { content.kind.takesParts },
                workerId = (target as? ReportTarget.Worker)?.workerId,
                printedNames = content.printedNames,
                sha256 = sha,
                byteSize = bytes.size,
            ),
        )
        return Outcome.Issued(bytes, id, sha)
    }

    private sealed interface Prepared {
        data class Ready(val content: ReportContent) : Prepared
        data object NoSuchPiece : Prepared
        data object NoSuchSession : Prepared
        data object NoSuchWorker : Prepared
        data class NotLinked(val code: String) : Prepared
        data object FinalRequired : Prepared
        data class Invalid(val code: String, val detail: String? = null) : Prepared
    }

    /**
     * The assessments of [piece] that [userId] may read (Nathan, 16 November 2026): its
     * own, and, for the account that runs the studio the piece is labelled for, the
     * maker's as well, except those the maker deleted from their phone, which the worker
     * page does not show either. The maker never reads the manager's assessments of their
     * piece, so nothing the maker asks for, a report or its preview, ever carries one.
     */
    private fun readable(userId: String, piece: ReportPiece, sessions: List<ReportSession>): List<ReportSession> =
        sessions.filter {
            it.userId == userId || (piece.userId != userId && it.userId == piece.userId && !it.clientDeleted)
        }

    /**
     * Every piece a worker-work report on roster entry [workerId] covers, each once, with
     * the assessments [userId] may read: its own pieces that name the worker, then the
     * worker's own pieces labelled for its studio (row 22).
     */
    private suspend fun coveredPieces(
        userId: String,
        workerId: String,
        workerAccountId: String,
    ): List<Pair<ReportPiece, List<ReportAssessment>>> =
        (store.piecesNaming(userId, workerId) + store.labelledPiecesOf(userId, workerAccountId))
            .distinctBy { it.piece.id }
            .map { (piece, sessions) -> piece to readable(userId, piece, sessions).map(ReportAssessment::of) }

    /** Checks the request, reads the target, and composes. Shared by the preview and the report. */
    private suspend fun prepare(userId: String, target: ReportTarget, request: ReportRequest): Prepared {
        val kind = ReportKind.fromId(request.kind)
            ?: return Prepared.Invalid("invalid_kind", "kind must be one of ${ReportKind.entries.joinToString { it.id }}")
        return when (target) {
            is ReportTarget.Piece -> preparePiece(userId, target.pieceId, kind, request)
            is ReportTarget.Worker -> prepareWorker(userId, target.workerId, kind, request)
        }
    }

    /**
     * The rules of plan v7 §5.4 and PR 4: `parts` on the two part kinds and no other;
     * `session_id` on every kind but the customer's, naming a listed (not client-deleted)
     * assessment of this piece, the newest by default; a fixes report needs an earlier
     * listed assessment with a verdict, so the piece's first assessment with a verdict
     * cannot be chosen for one. "My manager" needs a linked studio to send to.
     */
    private suspend fun preparePiece(userId: String, pieceId: String, kind: ReportKind, request: ReportRequest): Prepared {
        if (kind == ReportKind.WORKER_WORK) {
            return Prepared.Invalid("kind_not_for_piece", "a worker_work report is asked for on the worker, not a piece")
        }
        val parts = when {
            kind.takesParts -> {
                val given = request.parts.orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
                if (given.isEmpty()) return Prepared.Invalid("parts_required", "a ${kind.id} report names one or more parts")
                val parsed = given.map { WorkPart.fromId(it) ?: return Prepared.Invalid("invalid_part", "unknown part $it") }
                WorkPart.entries.filter { it in parsed }
            }
            request.parts != null -> return Prepared.Invalid("parts_not_allowed", "a ${kind.id} report takes no parts")
            else -> emptyList()
        }
        if (kind == ReportKind.CUSTOMER && request.sessionId != null) {
            return Prepared.Invalid("session_not_allowed", "a customer report covers every assessment")
        }
        if (request.pieceIds != null) {
            return Prepared.Invalid("piece_ids_not_allowed", "piece_ids narrows a worker_work report only")
        }
        if (request.studioId != null && kind != ReportKind.TO_MANAGER) {
            return Prepared.Invalid("studio_not_allowed", "studio_id names the studio of a to_manager report only")
        }
        val piece = store.piece(userId, pieceId) ?: return Prepared.NoSuchPiece
        val maker = piece.userId == userId
        // The share sheet picks the recipient and the app cannot restrict it; what the
        // server can hold is that "My manager" is sent by the piece's maker, about a piece
        // labelled for a studio it is linked to, and only to that studio (row 3). A piece
        // for "My own job" has no "My manager" report.
        if (kind == ReportKind.TO_MANAGER) {
            if (!maker) return Prepared.NotLinked("not_the_maker")
            if (piece.studioEntryId == null || piece.managerId == null) return Prepared.NotLinked("no_linked_studio")
            if (request.studioId != null && request.studioId != piece.studioEntryId) {
                return Prepared.NotLinked("not_the_pieces_studio")
            }
        }
        val assessments = readable(userId, piece, store.sessionsOf(pieceId)).map(ReportAssessment::of)
        // A customer report is the studio's: the account that runs the studio the piece is
        // labelled for, or the maker of a piece that is not labelled for one. It needs that
        // account's final assessment of the finished piece first (row 27); a problem the
        // final assessment finds does not block it, and the report lists the problem.
        if (kind == ReportKind.CUSTOMER) {
            val studio = if (piece.studioEntryId == null) piece.userId else piece.managerId
            if (studio != userId) return Prepared.NotLinked("customer_report_is_the_studios")
            val finished = assessments.any { it.listed && it.isFinal && it.userId == studio && it.verdict != null }
            if (!finished) return Prepared.FinalRequired
        }

        var chosen: ReportAssessment? = null
        var previous: ReportAssessment? = null
        if (kind.coversOneAssessment) {
            // Only the assessments the phone lists: a client-deleted one cannot be chosen
            // and does not count as the first, latest or previous. And only the caller's
            // own: these kinds print the names recorded on the assessment, and on a
            // worker's assessment those are the worker's roster and "Me", which would print
            // the manager's name for the worker's own work. The customer report is the one
            // that spans the worker's assessments, and it prints no name.
            val listed = assessments.filter { it.listed && it.userId == userId }
            chosen = when (val id = request.sessionId) {
                null -> listed.lastOrNull() ?: return Prepared.Invalid("no_assessment", "this piece has no assessment to report on")
                else -> listed.firstOrNull { it.sessionId == id } ?: return Prepared.NoSuchSession
            }
            if (kind == ReportKind.PART_FIXES) {
                previous = listed.take(listed.indexOf(chosen)).lastOrNull { it.verdict != null }
                    ?: return Prepared.Invalid(
                        "no_previous_assessment",
                        "a fixes report needs an earlier assessment of the piece with a verdict",
                    )
            }
        }
        val content = composer.compose(
            ReportInput(
                kind = kind,
                piece = piece,
                assessments = assessments,
                makerName = if (kind.isInternal) store.accountName(userId) else null,
                madeAt = clock(),
                chosen = chosen,
                previous = previous,
                parts = parts,
            ),
        )
        return Prepared.Ready(content)
    }

    /**
     * The linked-worker report: for one roster worker of this account, and only while the
     * two-sided match holds. A worker who is not on the roster is 404; one who is but is
     * not linked is refused, since the report exists for the link.
     *
     * Since v0.6.0 (row 22) it covers the worker's own assessments of their pieces labelled
     * for this studio too, each piece once, and `piece_ids` narrows it to chosen pieces:
     * every id must be one of the pieces it would cover, or the request is 404.
     */
    private suspend fun prepareWorker(userId: String, workerId: String, kind: ReportKind, request: ReportRequest): Prepared {
        if (kind != ReportKind.WORKER_WORK) {
            return Prepared.Invalid("kind_not_for_worker", "only a worker_work report is asked for on a worker")
        }
        if (request.parts != null || request.sessionId != null || request.studioId != null) {
            return Prepared.Invalid("fields_not_allowed", "a worker_work report takes no parts, no session and no studio")
        }
        if (request.pieceIds?.isEmpty() == true) {
            return Prepared.Invalid("piece_ids_empty", "piece_ids names one or more pieces, or is left out")
        }
        if (!store.ownsWorker(userId, workerId)) return Prepared.NoSuchWorker
        val worker = store.linkedWorker(userId, workerId) ?: return Prepared.NotLinked("worker_not_linked")
        val covered = coveredPieces(userId, workerId, worker.accountId)
        val pieces = when (val chosen = request.pieceIds?.toSet()) {
            null -> covered
            else -> {
                if (!covered.map { it.first.id }.containsAll(chosen)) return Prepared.NoSuchPiece
                covered.filter { it.first.id in chosen }
            }
        }
        val content = composer.composeWorkerWork(
            WorkerWorkInput(
                workerId = workerId,
                workerName = worker.name,
                workerAccountId = worker.accountId,
                pieces = pieces,
                makerName = store.accountName(userId),
                madeAt = clock(),
            ),
        )
        return Prepared.Ready(content)
    }

    /**
     * The cache key (§5.4): everything the file depends on. The blocks carry the piece's
     * name, the language, every line and every name printed; the photo list, the target
     * and the request's own fields cover the rest, and the builder version changes with
     * the layout. The date made is deliberately part of it, so a report is re-rendered
     * the next day.
     */
    private fun cacheKey(content: ReportContent, target: ReportTarget, request: ReportRequest): String = ReportCache.keyOf(
        listOf(
            BUILDER_VERSION,
            content.kind.id,
            target.toString(),
            content.parts.joinToString(",") { it.id },
            request.sessionId.orEmpty(),
            content.sessionIds.joinToString(","),
            content.photos.joinToString(","),
            json.encodeToString(ReportPreview.serializer(), content.preview()),
        ),
    )

    private companion object {
        /** Raised whenever the layout changes, so cached files from the old layout are not served. */
        const val BUILDER_VERSION = "4"

        val json = Json { encodeDefaults = false }
    }
}
