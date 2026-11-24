package com.qualityverifier.fundi.reports

import com.qualityverifier.data.reports.DownloadProgress
import com.qualityverifier.data.reports.PreviewOutcome
import com.qualityverifier.data.reports.ReportApi
import com.qualityverifier.data.reports.ReportOutcome
import com.qualityverifier.data.reports.ReportTarget
import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.VerdictLevel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The "Send report" flow over a fake API: the customer choice opens the preview the server
 * composed, "Send" downloads with progress and hands the whole file over, and a failure
 * leaves a retry and no file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SendReportViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    @get:Rule
    val folder = TemporaryFolder()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private val preview = ReportPreview(
        kind = "customer",
        pieceName = "Hotel table",
        blocks = listOf(
            HeadingBlock("Assessment of 12 Nov 2026"),
            ProblemBlock("Gap at the back joint", "Moderate", whatISee = "A gap.", whatItMeans = "It will loosen."),
        ),
        sessionIds = listOf("s1"),
        photoCount = 2,
    )

    /**
     * The piece with its final assessment made (v0.6.0, row 27), so the customer report
     * these tests ask for goes straight to its preview.
     */
    private val finished = MutableStateFlow(
        listOf(
            SessionSummary(
                id = "s1", itemType = ItemType.WOODEN_TABLE, createdAt = 1_000, updatedAt = 1_000,
                preview = "", messageCount = 4, verdictLevel = VerdictLevel.SOUND, pieceId = "piece-1",
                isFinal = true,
            ),
        ),
    )

    @Test
    fun `choosing the customer opens the preview with its request`() = runTest {
        val api = FakeApi(previewOutcome = PreviewOutcome.Loaded(preview))
        val model = SendReportViewModel(ReportTarget.Piece("piece-1"), api, fileFor = null, summaries = finished)
        advanceUntilIdle()

        assertEquals(ReportKind.CUSTOMER, model.offered.value.first())
        assertEquals(SendReportViewModel.Step.WhoFor, model.step.value)

        model.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()

        val step = model.step.value as SendReportViewModel.Step.Preview
        assertEquals(ReportRequest(kind = "customer"), step.request)
        assertEquals(preview, step.preview)
        assertEquals(listOf("piece-1" to ReportRequest(kind = "customer")), api.previewed)
        // No part control and no remove control exist: the preview is the server's blocks as they are.
        assertEquals(preview.blocks, step.preview.blocks)
    }

    // Row 51 (Nathan, 23 November 2026): on a piece labelled for a studio with no linked
    // manager, the customer report is the maker's to send; with a linked one it is not.
    private fun labelledFor(linked: Boolean) = SendReportViewModel(
        ReportTarget.Piece("piece-1"), FakeApi(previewOutcome = PreviewOutcome.Loaded(preview)), fileFor = null,
        summaries = MutableStateFlow(listOf(finished.value.single().copy(pieceStudioId = "entry-1"))),
        studios = MutableStateFlow(listOf(Studio("entry-1", "Mama Njeri's", "+254711000223", archived = false, linked = linked))),
    )

    @Test
    fun `row 51 - a maker with an unlinked studio is offered the customer report`() = runTest {
        val model = labelledFor(linked = false)
        advanceUntilIdle()
        assertTrue(model.offered.value.contains(ReportKind.CUSTOMER))
        assertFalse("no My manager without a link", model.offered.value.contains(ReportKind.TO_MANAGER))
    }

    @Test
    fun `row 51 - a maker whose studio manager is linked is not offered it`() = runTest {
        val model = labelledFor(linked = true)
        advanceUntilIdle()
        assertFalse(model.offered.value.contains(ReportKind.CUSTOMER))
        assertTrue(model.offered.value.contains(ReportKind.TO_MANAGER))
    }

    @Test
    fun `send downloads with progress, hands the file over, and keeps the preview behind the share sheet`() = runTest {
        val file = File(folder.newFolder(), "report.pdf")
        // The step is read as each progress call lands: a StateFlow keeps only the newest
        // value, so a collector would see the three updates as one.
        lateinit var model: SendReportViewModel
        val seen = mutableListOf<SendReportViewModel.Step>()
        val api = FakeApi(
            previewOutcome = PreviewOutcome.Loaded(preview),
            download = { into, progress ->
                seen += model.step.value
                progress.onProgress(0, 480_000)
                seen += model.step.value
                progress.onProgress(120_000, 480_000)
                seen += model.step.value
                into.writeBytes("pdf".toByteArray())
                progress.onProgress(480_000, 480_000)
                seen += model.step.value
                ReportOutcome.Done(into, "report-id")
            },
        )
        model = SendReportViewModel(ReportTarget.Piece("piece-1"), api, fileFor = { file }, summaries = finished)

        model.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()

        assertEquals(file, model.share.value)
        assertEquals(listOf("piece-1" to ReportRequest(kind = "customer")), api.downloaded)
        val request = ReportRequest(kind = "customer")
        assertEquals(
            listOf(
                SendReportViewModel.Step.Preparing(request),
                SendReportViewModel.Step.Downloading(request, 0, 480_000),
                SendReportViewModel.Step.Downloading(request, 120_000, 480_000),
                SendReportViewModel.Step.Downloading(request, 480_000, 480_000),
            ),
            seen,
        )
        assertTrue("the preview is back once the file is handed over", model.step.value is SendReportViewModel.Step.Preview)
    }

    @Test
    fun `a failed download shows the message with a retry, and no file is kept`() = runTest {
        val file = File(folder.newFolder(), "report.pdf")
        var attempts = 0
        val api = FakeApi(
            previewOutcome = PreviewOutcome.Loaded(preview),
            download = { into, _ ->
                attempts++
                if (attempts == 1) ReportOutcome.Unavailable else {
                    into.writeBytes("pdf".toByteArray())
                    ReportOutcome.Done(into, null)
                }
            },
        )
        val model = SendReportViewModel(ReportTarget.Piece("piece-1"), api, fileFor = { file }, summaries = finished)

        model.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()

        val failed = model.step.value as SendReportViewModel.Step.Failed
        assertEquals(ReportRequest(kind = "customer"), failed.request)
        assertFalse(failed.busy)
        assertFalse("no partial file", file.exists())

        model.retry()
        advanceUntilIdle()
        assertEquals(file, model.share.value)
        assertEquals(2, attempts)
        // Taken by the screen once the share sheet has opened.
        model.shared(file)
        assertNull(model.share.value)
    }

    @Test
    fun `a busy server is a failure the retry can clear, and a failed preview goes back to the start`() = runTest {
        val api = FakeApi(
            previewOutcome = PreviewOutcome.Loaded(preview),
            download = { _, _ -> ReportOutcome.Busy },
        )
        val model = SendReportViewModel(ReportTarget.Piece("piece-1"), api, fileFor = { File(folder.newFolder(), "r.pdf") }, summaries = finished)
        model.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()
        val failed = model.step.value as SendReportViewModel.Step.Failed
        assertTrue(failed.busy)

        val offline = SendReportViewModel(ReportTarget.Piece("piece-1"), FakeApi(previewOutcome = PreviewOutcome.Unavailable), fileFor = null, summaries = finished)
        offline.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        val noPreview = offline.step.value as SendReportViewModel.Step.Failed
        assertNull(noPreview.request)
        assertFalse(noPreview.refused)
        offline.retry()
        assertEquals(SendReportViewModel.Step.WhoFor, offline.step.value)

        // A 4xx is not a connection problem, and says so.
        val refused = SendReportViewModel(ReportTarget.Piece("piece-1"), FakeApi(previewOutcome = PreviewOutcome.Refused(404)), fileFor = null, summaries = finished)
        refused.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        assertTrue((refused.step.value as SendReportViewModel.Step.Failed).refused)
    }

    @Test
    fun `back while the preview loads wins over the preview that then arrives`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val model = SendReportViewModel(
            ReportTarget.Piece("piece-1"), FakeApi(previewOutcome = PreviewOutcome.Loaded(preview), gate = gate),
            fileFor = null, summaries = finished,
        )
        model.choose(ReportKind.CUSTOMER)
        runCurrent()
        assertTrue(model.step.value is SendReportViewModel.Step.LoadingPreview)
        model.back()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(SendReportViewModel.Step.WhoFor, model.step.value)
    }

    private class FakeApi(
        private val previewOutcome: PreviewOutcome,
        private val download: suspend (File, DownloadProgress) -> ReportOutcome = { _, _ -> ReportOutcome.Unavailable },
        /** Holds the preview until completed, so a test can act while it loads. */
        private val gate: CompletableDeferred<Unit>? = null,
    ) : ReportApi {
        val previewed = mutableListOf<Pair<String, ReportRequest>>()
        val downloaded = mutableListOf<Pair<String, ReportRequest>>()

        override suspend fun preview(target: ReportTarget, request: ReportRequest): PreviewOutcome {
            previewed += target.id to request
            gate?.await()
            return previewOutcome
        }

        override suspend fun download(
            target: ReportTarget,
            request: ReportRequest,
            into: File,
            progress: DownloadProgress,
        ): ReportOutcome {
            downloaded += target.id to request
            return download(into, progress)
        }
    }
}
