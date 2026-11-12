package com.qualityverifier.server.reports

import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
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
        data class Invalid(val code: String, val detail: String? = null) : Outcome
        data object Busy : Outcome
    }

    sealed interface PreviewOutcome {
        data class Ready(val preview: ReportPreview) : PreviewOutcome
        data object NoSuchPiece : PreviewOutcome
        data class Invalid(val code: String, val detail: String? = null) : PreviewOutcome
    }

    suspend fun preview(userId: String, pieceId: String, request: ReportRequest): PreviewOutcome =
        when (val prepared = prepare(userId, pieceId, request)) {
            is Prepared.Ready -> PreviewOutcome.Ready(prepared.content.preview())
            Prepared.NoSuchPiece -> PreviewOutcome.NoSuchPiece
            is Prepared.Invalid -> PreviewOutcome.Invalid(prepared.code, prepared.detail)
        }

    suspend fun issue(userId: String, pieceId: String, request: ReportRequest): Outcome {
        val content = when (val prepared = prepare(userId, pieceId, request)) {
            is Prepared.Ready -> prepared.content
            Prepared.NoSuchPiece -> return Outcome.NoSuchPiece
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
                parts = request.parts?.takeIf { content.kind.takesParts },
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
        data class Invalid(val code: String, val detail: String? = null) : Prepared
    }

    /** Checks the request, reads the piece, and composes. Shared by the preview and the report. */
    private suspend fun prepare(userId: String, pieceId: String, request: ReportRequest): Prepared {
        val kind = ReportKind.fromId(request.kind)
            ?: return Prepared.Invalid("invalid_kind", "kind must be one of ${ReportKind.entries.joinToString { it.id }}")
        if (kind != ReportKind.CUSTOMER) {
            // The other kinds arrive in later PRs; until then a request for one is a client bug.
            return Prepared.Invalid("kind_not_available", "only the customer report exists yet")
        }
        if (request.parts != null) return Prepared.Invalid("parts_not_allowed", "a ${kind.id} report takes no parts")
        if (request.sessionId != null) {
            return Prepared.Invalid("session_not_allowed", "a customer report covers every assessment")
        }
        val piece = store.piece(userId, pieceId) ?: return Prepared.NoSuchPiece
        val assessments = store.sessionsOf(pieceId).map(ReportAssessment::of)
        val content = composer.compose(
            ReportInput(
                kind = kind,
                piece = piece,
                assessments = assessments,
                makerName = if (kind.isInternal) store.accountName(userId) else null,
                madeAt = clock(),
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
            request.parts?.sorted()?.joinToString(",").orEmpty(),
            request.sessionId.orEmpty(),
            content.sessionIds.joinToString(","),
            content.photos.joinToString(","),
            json.encodeToString(ReportPreview.serializer(), content.preview()),
        ),
    )

    private companion object {
        /** Raised whenever the layout changes, so cached files from the old layout are not served. */
        const val BUILDER_VERSION = "1"

        val json = Json { encodeDefaults = false }
    }
}
