package com.qualityverifier.server.reports

import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.db.ReportRow
import com.qualityverifier.server.db.ReportStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Issues a report: reads the piece, composes it, serves it from the cache or renders it,
 * and writes its row (plan v7 §5.4).
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
        data class Invalid(val code: String, val detail: String? = null) : Outcome
        data object Busy : Outcome
    }

    sealed interface PreviewOutcome {
        data class Ready(val preview: ReportPreview) : PreviewOutcome
        data object NoSuchPiece : PreviewOutcome
        data object NoSuchSession : PreviewOutcome
        data class Invalid(val code: String, val detail: String? = null) : PreviewOutcome
    }

    suspend fun preview(userId: String, pieceId: String, request: ReportRequest): PreviewOutcome =
        when (val prepared = prepare(userId, pieceId, request)) {
            is Prepared.Ready -> PreviewOutcome.Ready(prepared.content.preview())
            Prepared.NoSuchPiece -> PreviewOutcome.NoSuchPiece
            Prepared.NoSuchSession -> PreviewOutcome.NoSuchSession
            is Prepared.Invalid -> PreviewOutcome.Invalid(prepared.code, prepared.detail)
        }

    suspend fun issue(userId: String, pieceId: String, request: ReportRequest): Outcome {
        val content = when (val prepared = prepare(userId, pieceId, request)) {
            is Prepared.Ready -> prepared.content
            Prepared.NoSuchPiece -> return Outcome.NoSuchPiece
            Prepared.NoSuchSession -> return Outcome.NoSuchSession
            is Prepared.Invalid -> return Outcome.Invalid(prepared.code, prepared.detail)
        }
        val key = cacheKey(content, request)
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
                pieceId = pieceId,
                userId = userId,
                kind = content.kind.id,
                sessionIds = content.sessionIds,
                defectRefs = content.defectRefs,
                defectSnapshot = json.encodeToString(ReportSnapshot.serializer(), content.snapshot),
                parts = content.parts.map { it.id }.takeIf { content.kind.takesParts },
                workerId = null,
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
        data class Invalid(val code: String, val detail: String? = null) : Prepared
    }

    /**
     * Checks the request, reads the piece, and composes. Shared by the preview and the
     * report, so the preview is of exactly the report "Send" will fetch.
     *
     * The rules of plan v7 §5.4 and PR 4: `parts` on the two part kinds and no other;
     * `session_id` on every kind but the customer's, naming a listed (not client-deleted)
     * assessment of this piece, the newest by default; a fixes report needs an earlier
     * listed assessment with a verdict, so the piece's first assessment with a verdict
     * cannot be chosen for one.
     */
    private suspend fun prepare(userId: String, pieceId: String, request: ReportRequest): Prepared {
        val kind = ReportKind.fromId(request.kind)
            ?: return Prepared.Invalid("invalid_kind", "kind must be one of ${ReportKind.entries.joinToString { it.id }}")
        if (kind !in BUILT) {
            // The other kinds arrive in a later PR; until then a request for one is a client bug.
            return Prepared.Invalid("kind_not_available", "the ${kind.id} report does not exist yet")
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
        val piece = store.piece(userId, pieceId) ?: return Prepared.NoSuchPiece
        val assessments = store.sessionsOf(pieceId).map(ReportAssessment::of)

        var chosen: ReportAssessment? = null
        var previous: ReportAssessment? = null
        if (kind.coversOneAssessment) {
            // Only the assessments the phone lists: a client-deleted one cannot be chosen
            // and does not count as the first, latest or previous.
            val listed = assessments.filter { it.listed }
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
     * The cache key (§5.4): everything the file depends on. The blocks carry the piece's
     * name, the language, every line and every name printed; the photo list and the
     * request's own fields cover the rest, and the builder version changes with the layout.
     * The date made is deliberately part of it, so a report is re-rendered the next day.
     */
    private fun cacheKey(content: ReportContent, request: ReportRequest): String = ReportCache.keyOf(
        listOf(
            BUILDER_VERSION,
            content.kind.id,
            content.parts.joinToString(",") { it.id },
            request.sessionId.orEmpty(),
            content.sessionIds.joinToString(","),
            content.photos.joinToString(","),
            json.encodeToString(ReportPreview.serializer(), content.preview()),
        ),
    )

    private companion object {
        /** Raised whenever the layout changes, so cached files from the old layout are not served. */
        const val BUILDER_VERSION = "2"

        /** The kinds this build composes. */
        val BUILT = setOf(ReportKind.CUSTOMER, ReportKind.PART_ISSUES, ReportKind.PART_FIXES, ReportKind.FULL)

        val json = Json { encodeDefaults = false }
    }
}
