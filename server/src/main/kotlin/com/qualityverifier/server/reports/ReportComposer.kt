package com.qualityverifier.server.reports

import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LineBlock
import com.qualityverifier.domain.PhotosBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.TextBlock
import com.qualityverifier.domain.Verdict
import com.qualityverifier.server.db.ReportPiece
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.db.ReportWorker
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.parseAssistantContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One assessment as the reports read it: its newest verdict, its problems through
 * [DefectList], and the reply they came from.
 */
data class ReportAssessment(
    val sessionId: String,
    val createdAt: Long,
    val clientDeleted: Boolean,
    val messageCount: Int,
    val purpose: FundiPurpose?,
    val verdict: Verdict?,
    val verdictLanguage: String?,
    val problems: DefectList,
    /**
     * The newest reply that sent a verdict or a diagnosis: the one the problem list was
     * read from, which names the problems in `reports.defect_refs`. A corrected verdict is
     * a newer reply, so its problems get new references. Null when no reply sent either.
     */
    val sourceMessageId: String?,
    val photos: List<String>,
    val workers: List<ReportWorker>,
) {
    /** Whether the maker's own tap on "Send report" can name this assessment: not deleted, as the home list shows. */
    val listed: Boolean get() = !clientDeleted

    companion object {
        fun of(session: ReportSession): ReportAssessment {
            val parsedNewestFirst = session.replies.asReversed().map { it.messageId to parseAssistantContent(it.text) }
            val newest = NewestBlocks.of(parsedNewestFirst.map { it.second })
            val purpose = FundiIntake.decode(session.intakeAnswers)?.second
            return ReportAssessment(
                sessionId = session.id,
                createdAt = session.createdAt,
                clientDeleted = session.clientDeleted,
                messageCount = session.messageCount,
                purpose = purpose,
                verdict = newest.verdict,
                verdictLanguage = session.verdictLanguage?.takeIf { it.isNotBlank() }
                    ?: newest.verdict?.language?.takeIf { it.isNotBlank() },
                problems = DefectList.fromReplies(parsedNewestFirst.map { it.second }, purpose),
                sourceMessageId = parsedNewestFirst
                    .firstOrNull { (_, reply) -> reply.verdict != null || reply.sentDiagnosis }
                    ?.first,
                photos = session.photos,
                workers = session.workers,
            )
        }
    }
}

/** What one report is composed from. */
data class ReportInput(
    val kind: ReportKind,
    val piece: ReportPiece,
    /** Every assessment of the piece, oldest first, deleted ones included. */
    val assessments: List<ReportAssessment>,
    /** The name of the account making the report, printed on every kind but the customer's. */
    val makerName: String?,
    /** The server's clock when the report is made. */
    val madeAt: Long,
)

/**
 * What a report printed, for `reports.defect_snapshot`: each assessment's level and
 * headline, each problem's labels and part, the worker names under each part, and who made
 * it. Survives the purge of a deleted session and any later parser change.
 */
@Serializable
data class ReportSnapshot(
    val assessments: List<SnapshotAssessment>,
    val problems: List<SnapshotProblem>,
    val workers: List<SnapshotWorker>,
    @SerialName("made_by") val madeBy: String? = null,
)

@Serializable
data class SnapshotAssessment(
    @SerialName("session_id") val sessionId: String,
    val level: String?,
    val headline: String?,
)

@Serializable
data class SnapshotProblem(
    @SerialName("session_id") val sessionId: String,
    @SerialName("message_id") val messageId: String?,
    val ordinal: Int,
    val title: String,
    val severity: String,
    /** The part the report printed it under; null on a customer report, which prints none. */
    val part: String?,
)

@Serializable
data class SnapshotWorker(
    @SerialName("session_id") val sessionId: String,
    val part: String?,
    @SerialName("worker_id") val workerId: String?,
    val name: String,
)

/** A composed report: the blocks the PDF is drawn from, and what the row records. */
data class ReportContent(
    val kind: ReportKind,
    val pieceName: String,
    val labels: ReportLabels,
    val blocks: List<ReportBlock>,
    val sessionIds: List<String>,
    val defectRefs: List<String>,
    val snapshot: ReportSnapshot,
    /** Every name printed: the workers under each part and the maker. Empty on a customer report. */
    val printedNames: List<String>,
) {
    /** The photos the report prints, in order: the first block's first, so dropping from the front drops the oldest. */
    val photos: List<String> get() = blocks.filterIsInstance<PhotosBlock>().flatMap { it.sha256 }

    fun preview(): ReportPreview = ReportPreview(
        kind = kind.id,
        pieceName = pieceName,
        blocks = blocks,
        sessionIds = sessionIds,
        photoCount = photos.size,
    )
}

/**
 * Turns an assessment or a piece into the lines a report prints (plan v7 §5.2).
 *
 * Pure: the store reads, the renderer draws, and everything in between is here, so the
 * rules of what each reader gets can be tested on made-up assessments with no database and
 * no PDF. The blocks it returns are the preview the phone shows and the input the renderer
 * draws, so the two cannot differ.
 */
class ReportComposer {

    fun compose(input: ReportInput): ReportContent = when (input.kind) {
        ReportKind.CUSTOMER -> customer(input)
        else -> throw IllegalArgumentException("kind ${input.kind.id} is not composed yet")
    }

    /**
     * The customer report: every assessment of the piece in date order, each with its date,
     * verdict level and headline, its photos, and its problems with what was seen and what it
     * means. Nothing addressed to the maker (summary, what to do, unverified, questions), no
     * worker name, no part, and no assessor. The newest verdict closes it.
     */
    private fun customer(input: ReportInput): ReportContent {
        val labels = labelsFor(input.assessments)
        val pdf = labels.pdf
        val pieceName = pieceName(input.piece, labels)
        val blocks = mutableListOf<ReportBlock>()
        blocks += HeadingBlock(pdf.title(ReportKind.CUSTOMER), level = 1)
        blocks += LineBlock(pdf.piece, pieceName)
        blocks += TextBlock(pdf.madeOn(day(input.madeAt)))

        val photoPlan = PhotoPlan.of(input.assessments.map { it.photos })
        val problems = mutableListOf<SnapshotProblem>()
        val refs = mutableListOf<String>()
        input.assessments.forEachIndexed { index, assessment ->
            blocks += HeadingBlock(assessmentHeading(assessment, labels), level = 2)
            blocks += verdictLine(assessment, labels)
            photoPlan.forAssessment(index).takeIf { it.isNotEmpty() }?.let { blocks += PhotosBlock(it) }
            blocks += problemBlocks(assessment, labels, forCustomer = true)
            assessment.problems.problems.forEachIndexed { ordinal, problem ->
                problems += SnapshotProblem(
                    sessionId = assessment.sessionId,
                    messageId = assessment.sourceMessageId,
                    ordinal = ordinal,
                    title = problem.title,
                    severity = problem.severity,
                    part = null,
                )
                refs += "${assessment.sourceMessageId}:$ordinal"
            }
        }
        input.assessments.lastOrNull { it.verdict != null }?.let { latest ->
            blocks += HeadingBlock(pdf.latestVerdict, level = 2)
            blocks += LineBlock(pdf.verdict, verdictWords(latest.verdict!!, labels))
        }
        blocks += TextBlock(pdf.madeWith)

        return ReportContent(
            kind = ReportKind.CUSTOMER,
            pieceName = pieceName,
            labels = labels,
            blocks = blocks,
            sessionIds = input.assessments.map { it.sessionId },
            defectRefs = refs,
            snapshot = ReportSnapshot(
                assessments = input.assessments.map { it.snapshot() },
                problems = problems,
                workers = emptyList(),
                madeBy = null,
            ),
            printedNames = emptyList(),
        )
    }

    // ------------------------------------------------------------ shared pieces

    /** "Assessment of 12 Nov 2026", with "Not finished" after one the maker never completed. */
    private fun assessmentHeading(assessment: ReportAssessment, labels: ReportLabels): String {
        val heading = labels.pdf.assessmentOf(day(assessment.createdAt))
        // One turn and no verdict: started and abandoned, as the home list marks it.
        return if (assessment.verdict == null && assessment.messageCount <= 1) {
            "$heading (${labels.pdf.notFinished})"
        } else {
            heading
        }
    }

    private fun verdictLine(assessment: ReportAssessment, labels: ReportLabels): ReportBlock {
        val verdict = assessment.verdict ?: return TextBlock(labels.pdf.noFullVerdict)
        return LineBlock(labels.pdf.verdict, verdictWords(verdict, labels))
    }

    /** "Fair — Strong table, one joint to close", or the level alone when the verdict has no headline. */
    private fun verdictWords(verdict: Verdict, labels: ReportLabels): String {
        val word = labels.verdictWord(verdict)
        val headline = verdict.headline.trim()
        return if (headline.isEmpty()) word else "$word — $headline"
    }

    /**
     * The assessment's problems as blocks, or the one line that says why there are none.
     * For a customer, what to do is left out and no part is printed.
     */
    private fun problemBlocks(
        assessment: ReportAssessment,
        labels: ReportLabels,
        forCustomer: Boolean,
    ): List<ReportBlock> {
        val list = assessment.problems
        if (list.unknown) return listOf(TextBlock(labels.pdf.problemsUnknown))
        if (list.problems.isEmpty()) {
            // Only an assessment with a verdict says "nothing recorded": one without has
            // already said "No full verdict", and a second line would overstate it.
            return if (assessment.verdict != null) listOf(TextBlock(labels.pdf.noProblems)) else emptyList()
        }
        return list.problems.map { problem ->
            ProblemBlock(
                title = problem.title,
                severity = labels.severity(Severity.fromId(problem.severity)),
                part = if (forCustomer) null else problem.defaultPart?.let { labels.pdf.part(it) },
                whatISee = problem.whatISee,
                whatItMeans = problem.whatItMeans,
                whatToDo = if (forCustomer) null else problem.whatToDo,
            )
        }
    }

    private fun ReportAssessment.snapshot() = SnapshotAssessment(
        sessionId = sessionId,
        level = verdict?.level?.id?.takeIf { it.isNotEmpty() },
        headline = verdict?.headline?.trim()?.takeIf { it.isNotEmpty() },
    )

    /** The piece's name as the maker gave it, else the kind of thing it is. */
    private fun pieceName(piece: ReportPiece, labels: ReportLabels): String =
        piece.label?.trim()?.takeIf { it.isNotEmpty() }
            ?: ItemType.fromId(piece.itemTypeId)?.let(labels::itemName)
            ?: piece.itemTypeId

    /**
     * The language of the newest verdict that declared one, else English: one language for
     * the whole report, since its headings cannot change half way down.
     */
    private fun labelsFor(assessments: List<ReportAssessment>): ReportLabels =
        ReportLabels.forLanguage(assessments.lastOrNull { it.verdictLanguage != null }?.verdictLanguage)

    companion object {
        /** Nairobi, like every other date in this project. */
        private val DAY: DateTimeFormatter =
            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.UK).withZone(ZoneId.of("Africa/Nairobi"))

        fun day(epochMillis: Long): String = DAY.format(Instant.ofEpochMilli(epochMillis))
    }
}

/**
 * Which photos a report prints (plan v7 §5.3): the opening photo of each assessment first,
 * then the rest in order, until [MAX_PHOTOS]. Each photo is printed under its own
 * assessment, so a later assessment's opening shot can take the place of an earlier
 * assessment's third photo.
 */
class PhotoPlan private constructor(private val chosen: List<List<String>>) {

    fun forAssessment(index: Int): List<String> = chosen.getOrElse(index) { emptyList() }

    companion object {
        /** Eight, the budget that keeps a report under the size cap at the first photo settings. */
        const val MAX_PHOTOS = 8

        fun of(photosByAssessment: List<List<String>>): PhotoPlan {
            val kept = photosByAssessment.map { mutableListOf<String>() }
            var budget = MAX_PHOTOS
            // Opening shots first, one per assessment in date order.
            photosByAssessment.forEachIndexed { index, photos ->
                if (budget > 0 && photos.isNotEmpty()) {
                    kept[index] += photos.first()
                    budget--
                }
            }
            // Then the rest, in order, until the budget is spent.
            photosByAssessment.forEachIndexed { index, photos ->
                photos.drop(1).forEach { sha ->
                    if (budget > 0) {
                        kept[index] += sha
                        budget--
                    }
                }
            }
            return PhotoPlan(kept)
        }
    }
}
