package com.qualityverifier.fundi.reports

import com.qualityverifier.data.reports.DownloadProgress
import com.qualityverifier.data.reports.PreviewOutcome
import com.qualityverifier.data.reports.ReportApi
import com.qualityverifier.data.reports.ReportOutcome
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.ReportRequest
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.domain.VerdictLevel
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.WorkerSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The worker and Me steps (plan v7 PR 4): the parts named on the piece come first, "Fixes"
 * is offered only with two verdicts and leaves out the first assessment, a one-assessment
 * piece skips "Which assessment?", and unsent worker edits keep the preview closed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReportStepsTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun summary(id: String, createdAt: Long, verdict: VerdictLevel?) = SessionSummary(
        id = id, itemType = ItemType.WOODEN_TABLE, createdAt = createdAt, updatedAt = createdAt,
        preview = "", messageCount = 4, verdictLevel = verdict, pieceId = "piece-1",
    )

    private val one = listOf(summary("s1", 1_000, VerdictLevel.FAIR))
    private val two = one + summary("s2", 2_000, VerdictLevel.SOUND)
    private val three = two + summary("s3", 3_000, null)

    private fun model(
        summaries: List<SessionSummary>,
        named: Map<String, List<WorkerSlot>> = mapOf("s1" to listOf(WorkerSlot("w1", part = WorkPart.JOINERY))),
        pushed: Boolean = true,
        api: ReportApi = FakeApi(),
    ) = SendReportViewModel(
        pieceId = "piece-1",
        reports = api,
        fileFor = null,
        summaries = MutableStateFlow(summaries + summary("elsewhere", 5_000, VerdictLevel.FAIR).copy(pieceId = "piece-2")),
        workersOf = { id -> named[id].orEmpty() },
        pushWorkers = { pushed },
    )

    @Test
    fun `the first step offers the customer, a worker and me`() {
        assertEquals(listOf(ReportKind.CUSTOMER, ReportKind.PART_ISSUES, ReportKind.FULL), model(one).offered)
    }

    @Test
    fun `a worker report asks for parts, the named ones first and selected, then issues or fixes with two verdicts`() = runTest {
        val model = model(two)
        model.choose(ReportKind.PART_ISSUES)
        advanceUntilIdle()

        val parts = model.step.value as SendReportViewModel.Step.ChooseParts
        assertEquals(WorkPart.JOINERY, parts.offered.first())
        // A table offers no upholstery.
        assertEquals((WorkPart.entries - WorkPart.UPHOLSTERY).toSet(), parts.offered.toSet())
        assertEquals(setOf(WorkPart.JOINERY), parts.chosen)

        model.togglePart(WorkPart.FINISHING)
        model.partsChosen()
        advanceUntilIdle()
        val choice = model.step.value as SendReportViewModel.Step.IssuesOrFixes
        assertEquals(listOf(WorkPart.JOINERY, WorkPart.FINISHING), choice.parts)

        // Issues on the same piece: both assessments offered, newest first.
        model.chooseIssuesOrFixes(ReportKind.PART_ISSUES)
        advanceUntilIdle()
        val which = model.step.value as SendReportViewModel.Step.WhichAssessment
        assertEquals(listOf("s2", "s1"), which.options.map { it.sessionId })
        assertEquals(listOf(WorkPart.JOINERY, WorkPart.FINISHING), which.parts)
    }

    @Test
    fun `fixes leaves out the first assessment and a single option skips the step`() = runTest {
        val api = FakeApi()
        val model = model(two, api = api)
        model.choose(ReportKind.PART_ISSUES)
        advanceUntilIdle()
        model.partsChosen()
        advanceUntilIdle()
        model.chooseIssuesOrFixes(ReportKind.PART_FIXES)
        advanceUntilIdle()

        // Two assessments, the first excluded: one option, straight to the preview with it.
        assertTrue(model.step.value is SendReportViewModel.Step.Preview)
        assertEquals(
            ReportRequest(kind = "part_fixes", parts = listOf("joinery"), sessionId = "s2"),
            api.previewed.single().second,
        )
    }

    @Test
    fun `which assessment lists newest first with the newest selected, and issues keeps every assessment`() = runTest {
        val model = model(three)
        model.choose(ReportKind.FULL)
        advanceUntilIdle()

        val which = model.step.value as SendReportViewModel.Step.WhichAssessment
        assertEquals(listOf("s3", "s2", "s1"), which.options.map { it.sessionId })
        assertEquals("s3", which.selected)
        assertEquals(ReportKind.FULL, which.kind)

        model.selectAssessment("s1")
        model.assessmentChosen()
        advanceUntilIdle()
        val preview = model.step.value as SendReportViewModel.Step.Preview
        assertEquals(ReportRequest(kind = "full", sessionId = "s1"), preview.request)
    }

    @Test
    fun `a one-assessment piece skips which assessment, and fixes is not offered`() = runTest {
        val api = FakeApi()
        val model = model(one, api = api)
        model.choose(ReportKind.PART_ISSUES)
        advanceUntilIdle()
        model.partsChosen()
        advanceUntilIdle()

        assertTrue(model.step.value is SendReportViewModel.Step.Preview)
        assertEquals(
            ReportRequest(kind = "part_issues", parts = listOf("joinery"), sessionId = "s1"),
            api.previewed.single().second,
        )
    }

    @Test
    fun `unsent worker edits keep the preview closed, and the retry pushes again`() = runTest {
        var pushed = false
        val api = FakeApi()
        val model = SendReportViewModel(
            pieceId = "piece-1", reports = api, fileFor = null,
            summaries = MutableStateFlow(one), workersOf = { emptyList() }, pushWorkers = { pushed },
        )
        model.choose(ReportKind.FULL)
        advanceUntilIdle()

        val failed = model.step.value as SendReportViewModel.Step.Failed
        assertTrue(failed.workersUnsent)
        assertTrue(api.previewed.isEmpty())

        pushed = true
        model.retry()
        advanceUntilIdle()
        assertTrue(model.step.value is SendReportViewModel.Step.Preview)
    }

    @Test
    fun `the customer report sends nothing first`() = runTest {
        val api = FakeApi()
        val model = SendReportViewModel(
            pieceId = "piece-1", reports = api, fileFor = null,
            summaries = MutableStateFlow(one), workersOf = { emptyList() }, pushWorkers = { false },
        )
        model.choose(ReportKind.CUSTOMER)
        advanceUntilIdle()
        assertTrue(model.step.value is SendReportViewModel.Step.Preview)
    }

    private class FakeApi : ReportApi {
        val previewed = mutableListOf<Pair<String, ReportRequest>>()
        override suspend fun preview(pieceId: String, request: ReportRequest): PreviewOutcome {
            previewed += pieceId to request
            return PreviewOutcome.Loaded(ReportPreview(request.kind, "Hotel table", "en", emptyList(), listOfNotNull(request.sessionId), 0))
        }
        override suspend fun download(pieceId: String, request: ReportRequest, into: File, progress: DownloadProgress): ReportOutcome =
            ReportOutcome.Unavailable
    }
}
