package com.qualityverifier.fundi.reports

import com.qualityverifier.data.reports.DownloadProgress
import com.qualityverifier.data.reports.PreviewOutcome
import com.qualityverifier.data.reports.ReportApi
import com.qualityverifier.data.reports.ReportOutcome
import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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

    @Test
    fun `only the customer report is offered, and choosing it opens the preview with its request`() = runTest {
        val api = FakeApi(previewOutcome = PreviewOutcome.Loaded(preview))
        val model = SendReportViewModel("piece-1", api, fileFor = null)

        assertEquals(listOf(ReportKind.CUSTOMER), model.offered)
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
        model = SendReportViewModel("piece-1", api, fileFor = { file })

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
        val model = SendReportViewModel("piece-1", api, fileFor = { file })

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
        val model = SendReportViewModel("piece-1", api, fileFor = { File(folder.newFolder(), "r.pdf") })
        model.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        model.send()
        advanceUntilIdle()
        val failed = model.step.value as SendReportViewModel.Step.Failed
        assertTrue(failed.busy)

        val offline = SendReportViewModel("piece-1", FakeApi(previewOutcome = PreviewOutcome.Unavailable), fileFor = null)
        offline.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        val noPreview = offline.step.value as SendReportViewModel.Step.Failed
        assertNull(noPreview.request)
        assertFalse(noPreview.refused)
        offline.retry()
        assertEquals(SendReportViewModel.Step.WhoFor, offline.step.value)

        // A 4xx is not a connection problem, and says so.
        val refused = SendReportViewModel("piece-1", FakeApi(previewOutcome = PreviewOutcome.Refused(404)), fileFor = null)
        refused.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        assertTrue((refused.step.value as SendReportViewModel.Step.Failed).refused)
    }

    @Test
    fun `back while the preview loads wins over the preview that then arrives`() = runTest {
        val model = SendReportViewModel("piece-1", FakeApi(previewOutcome = PreviewOutcome.Loaded(preview)), fileFor = null)
        model.choose(ReportKind.CUSTOMER)
        assertTrue(model.step.value is SendReportViewModel.Step.LoadingPreview)
        model.back()
        advanceUntilIdle()
        assertEquals(SendReportViewModel.Step.WhoFor, model.step.value)
    }

    private class FakeApi(
        private val previewOutcome: PreviewOutcome,
        private val download: suspend (File, DownloadProgress) -> ReportOutcome = { _, _ -> ReportOutcome.Unavailable },
    ) : ReportApi {
        val previewed = mutableListOf<Pair<String, ReportRequest>>()
        val downloaded = mutableListOf<Pair<String, ReportRequest>>()

        override suspend fun preview(pieceId: String, request: ReportRequest): PreviewOutcome {
            previewed += pieceId to request
            return previewOutcome
        }

        override suspend fun download(
            pieceId: String,
            request: ReportRequest,
            into: File,
            progress: DownloadProgress,
        ): ReportOutcome {
            downloaded += pieceId to request
            return download(into, progress)
        }
    }
}
