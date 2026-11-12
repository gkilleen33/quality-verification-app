package com.qualityverifier.server.reports

import com.qualityverifier.domain.FundiIntake
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.HeadingBlock
import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.LineBlock
import com.qualityverifier.domain.NamesBlock
import com.qualityverifier.domain.NoteBlock
import com.qualityverifier.domain.PhotosBlock
import com.qualityverifier.domain.ProblemBlock
import com.qualityverifier.domain.ReportBlock
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ReportPreview
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.TextBlock
import com.qualityverifier.domain.Verdict
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.domain.WorkerParts
import com.qualityverifier.server.db.ReportPiece
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.db.ReportWorker
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.parseAssistantContent
import com.qualityverifier.text.writesConsequences
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
     * The reply the problem list was read from, which names the problems in
     * `reports.defect_refs`: the newest verdict's reply when its defects are the list, else
     * the newest diagnosis's reply when its findings are. A corrected verdict is a newer
     * reply, so its problems get new references. Null when the list is empty.
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
            val problems = DefectList.fromReplies(parsedNewestFirst.map { it.second }, purpose)
            return ReportAssessment(
                sessionId = session.id,
                createdAt = session.createdAt,
                clientDeleted = session.clientDeleted,
                messageCount = session.messageCount,
                purpose = purpose,
                verdict = newest.verdict,
                verdictLanguage = session.verdictLanguage?.takeIf { it.isNotBlank() }
                    ?: newest.verdict?.language?.takeIf { it.isNotBlank() },
                problems = problems,
                sourceMessageId = sourceOf(parsedNewestFirst, problems),
                photos = session.photos,
                workers = session.workers,
            )
        }

        /**
         * Which reply [DefectList] took the list from, by its own rules (`DefectList.fromReplies`
         * and `problemsOf`): a one-list diagnosis gives its findings; otherwise a verdict with
         * defects gives those, and a verdict without gives the newest diagnosis's findings
         * (which a newer-or-equal diagnosis supplies, and an older one does not). The ordinals
         * in `defect_refs` index that reply's list.
         */
        private fun sourceOf(parsedNewestFirst: List<Pair<String, com.qualityverifier.text.AssistantContent>>, list: DefectList): String? {
            if (list.unknown || list.problems.isEmpty()) return null
            val verdictEntry = parsedNewestFirst.firstOrNull { it.second.verdict != null }
            val diagnosisEntry = parsedNewestFirst.firstOrNull { it.second.sentDiagnosis }
            val diagnosis = diagnosisEntry?.second?.diagnosis
            val verdict = verdictEntry?.second?.verdict
            return when {
                diagnosis != null && diagnosis.writesConsequences -> diagnosisEntry.first
                verdict != null && verdict.defects.isNotEmpty() -> verdictEntry.first
                else -> diagnosisEntry?.first
            }
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
    /** The assessment a one-assessment kind covers; null for the customer kind. */
    val chosen: ReportAssessment? = null,
    /** For a fixes report: the most recent earlier listed assessment with a verdict. */
    val previous: ReportAssessment? = null,
    /** The parts a part report covers, in [WorkPart] order. */
    val parts: List<WorkPart> = emptyList(),
)

/** What a linked-worker report is composed from: one roster worker's work across pieces. */
data class WorkerWorkInput(
    val workerId: String,
    val workerName: String,
    /** Every piece the worker was named on, oldest first, each with every assessment of the piece. */
    val pieces: List<Pair<ReportPiece, List<ReportAssessment>>>,
    val makerName: String?,
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
    /** The parts a part report covers, in order; empty for every other kind. */
    val parts: List<WorkPart> = emptyList(),
) {
    /** The photos the report prints, in order: the first block's first, so dropping from the front drops the oldest. */
    val photos: List<String> get() = blocks.filterIsInstance<PhotosBlock>().flatMap { it.sha256 }

    fun preview(): ReportPreview = ReportPreview(
        kind = kind.id,
        pieceName = pieceName,
        language = labels.code,
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
 *
 * No kind computes or prints a score, a count per worker, or a blame: a name sits beside a
 * part's problems because that worker was recorded on the part, and the manager judges.
 */
class ReportComposer {

    fun compose(input: ReportInput): ReportContent = when (input.kind) {
        ReportKind.CUSTOMER -> customer(input)
        ReportKind.PART_ISSUES -> partIssues(input)
        ReportKind.PART_FIXES -> partFixes(input)
        // "My manager": the full report, sent from the worker's own account to a linked
        // studio, with the sender's name at the top as every internal kind prints it.
        ReportKind.FULL, ReportKind.TO_MANAGER -> full(input)
        ReportKind.WORKER_WORK -> throw IllegalArgumentException("a worker-work report is composed by composeWorkerWork")
    }

    /**
     * The linked-worker report (Nathan, 12 November 2026): one roster worker's work across
     * pieces, for the worker or the studio. For each piece the worker was named on, in date
     * order: the piece, then each assessment they were named on with its date and verdict,
     * and for each part they did, under "Worked on this part: <name>", the problems placed
     * on it, each with what was seen, what it means, and what to do. A whole-piece naming
     * covers every part a problem was placed on. It prints no score, no count, and no
     * rating, and never calls a problem the worker's fault.
     */
    fun composeWorkerWork(input: WorkerWorkInput): ReportContent {
        val named = input.pieces.flatMap { it.second }
            .filter { assessment -> assessment.listed && assessment.workers.any { it.workerId == input.workerId } }
        val labels = labelsFor(named)
        val pdf = labels.pdf
        val record = Record()
        val blocks = mutableListOf<ReportBlock>()
        blocks += HeadingBlock(pdf.title(ReportKind.WORKER_WORK, input.workerName), level = 1)
        input.makerName?.let { blocks += TextBlock(pdf.madeBy(it)) }
        blocks += TextBlock(pdf.madeOn(day(input.madeAt)))
        record.names += input.workerName

        for ((piece, assessments) in input.pieces) {
            val onPiece = assessments
                .filter { it.listed && it.workers.any { row -> row.workerId == input.workerId } }
                .sortedBy { it.createdAt }
            if (onPiece.isEmpty()) continue
            blocks += HeadingBlock(pieceName(piece, labels), level = 2)
            for (assessment in onPiece) {
                blocks += HeadingBlock(assessmentHeading(assessment, labels), level = 3)
                blocks += verdictLine(assessment, labels)
                val rows = assessment.workers.filter { it.workerId == input.workerId }
                val wholePiece = rows.any { it.part == null }
                if (wholePiece) blocks += LineBlock(pdf.workedOn, pdf.wholePiece)
                if (assessment.problems.unknown) {
                    blocks += TextBlock(pdf.problemsUnknown)
                    continue
                }
                val placed = assessment.problems.problems.withIndex().groupBy { it.value.defaultPart }
                // The parts they did; on the whole piece, every part a problem was placed on.
                val parts = if (wholePiece) WorkPart.entries.filter { placed[it] != null }
                else WorkPart.entries.filter { part -> rows.any { it.part == part } }
                if (parts.isEmpty()) {
                    if (assessment.verdict != null) blocks += TextBlock(pdf.noProblems)
                    continue
                }
                for (part in parts) {
                    blocks += HeadingBlock(pdf.part(part), level = 4)
                    blocks += NamesBlock(pdf.workedOnThisPart, listOf(input.workerName))
                    val row = SnapshotWorker(assessment.sessionId, if (wholePiece) null else part.id, input.workerId, input.workerName)
                    if (row !in record.workers) record.workers += row
                    blocks += partProblems(assessment, part, labels, record)
                }
            }
        }
        input.makerName?.let { blocks += TextBlock(pdf.madeBy(it)) }
        blocks += TextBlock(pdf.madeWith)
        return ReportContent(
            kind = ReportKind.WORKER_WORK,
            pieceName = input.workerName,
            labels = labels,
            blocks = blocks,
            sessionIds = named.map { it.sessionId },
            defectRefs = record.refs,
            snapshot = ReportSnapshot(
                assessments = named.map { it.snapshot() },
                problems = record.problems,
                workers = record.workers,
                madeBy = input.makerName,
            ),
            printedNames = (record.names + listOfNotNull(input.makerName)).distinct(),
        )
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
        val record = Record()
        input.assessments.forEachIndexed { index, assessment ->
            blocks += HeadingBlock(assessmentHeading(assessment, labels), level = 2)
            blocks += verdictLine(assessment, labels)
            photoPlan.forAssessment(index).takeIf { it.isNotEmpty() }?.let { blocks += PhotosBlock(it) }
            blocks += problemBlocks(assessment, assessment.problems.problems.indices.toList(), labels, forCustomer = true, record = record)
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
            defectRefs = record.refs,
            snapshot = ReportSnapshot(
                assessments = input.assessments.map { it.snapshot() },
                problems = record.problems,
                workers = emptyList(),
                madeBy = null,
            ),
            printedNames = emptyList(),
        )
    }

    /**
     * The part report for a worker (plan v7 §5.2): the chosen assessment's problems on the
     * chosen parts, each with what was seen, what it means, and what to do, and under each
     * part the workers recorded on it. A re-assessment judges the whole piece afresh, so
     * nothing is carried from earlier assessments.
     */
    private fun partIssues(input: ReportInput): ReportContent {
        val chosen = requireNotNull(input.chosen) { "a part report needs its assessment" }
        val labels = labelsFor(listOf(chosen))
        val pdf = labels.pdf
        val record = Record()
        val blocks = mutableListOf<ReportBlock>()
        blocks += opening(input, ReportKind.PART_ISSUES, chosen, labels)
        val everyPart = coversEveryPart(input)
        for (part in input.parts) {
            blocks += HeadingBlock(pdf.part(part), level = 3)
            blocks += namesBlock(pdf.workedOnThisPart, chosen, part, everyPart, input.makerName, record)
            blocks += partProblems(chosen, part, labels, record)
        }
        blocks += closing(input, labels)
        return content(input, ReportKind.PART_ISSUES, listOf(chosen), blocks, labels, record)
    }

    /**
     * The fixes report (plan v7 §5.2): on a re-assessment, for each chosen part, last time's
     * problems on the part and this time's. It never marks a problem fixed, because the
     * verdict records no such judgement; the note at the end says what a missing problem
     * can mean. The names are those recorded at the re-assessment, the people who did the
     * fixes.
     */
    private fun partFixes(input: ReportInput): ReportContent {
        val chosen = requireNotNull(input.chosen) { "a fixes report needs its assessment" }
        val previous = requireNotNull(input.previous) { "a fixes report needs the assessment before" }
        val labels = labelsFor(listOf(chosen))
        val pdf = labels.pdf
        val record = Record()
        val blocks = mutableListOf<ReportBlock>()
        blocks += opening(input, ReportKind.PART_FIXES, chosen, labels)
        val everyPart = coversEveryPart(input)
        for (part in input.parts) {
            blocks += HeadingBlock(pdf.part(part), level = 3)
            blocks += namesBlock(pdf.workedOnThisPartThisAssessment, chosen, part, everyPart, input.makerName, record)
            blocks += HeadingBlock(pdf.foundInAssessmentOf(day(previous.createdAt)), level = 4)
            blocks += partProblems(previous, part, labels, record)
            blocks += HeadingBlock(pdf.foundThisTime, level = 4)
            blocks += partProblems(chosen, part, labels, record)
        }
        blocks += NoteBlock(pdf.missingMayBeFixed)
        blocks += closing(input, labels)
        return content(input, ReportKind.PART_FIXES, listOf(previous, chosen), blocks, labels, record)
    }

    /**
     * The full report (plan v7 §5.2): every problem of the chosen assessment, grouped by
     * part with "Part not given" last, the workers recorded on each part under its heading,
     * and the workers recorded on the whole piece once, above the first part.
     */
    private fun full(input: ReportInput): ReportContent {
        val chosen = requireNotNull(input.chosen) { "a full report needs its assessment" }
        val labels = labelsFor(listOf(chosen))
        val pdf = labels.pdf
        val record = Record()
        val blocks = mutableListOf<ReportBlock>()
        blocks += opening(input, input.kind, chosen, labels)
        blocks += namesBlock(pdf.alsoWorkedOn, chosen, part = null, wholePieceToo = false, input.makerName, record)
        val problems = chosen.problems
        val partsNamed = chosen.workers.mapNotNull { it.part }.toSet()
        if (problems.unknown) {
            // The names recorded on each part are still printed; only the problems are not known.
            blocks += TextBlock(pdf.problemsUnknown)
            for (part in WorkPart.entries.filter { it in partsNamed }) {
                blocks += HeadingBlock(pdf.part(part), level = 3)
                blocks += namesBlock(pdf.workedOnThisPart, chosen, part, wholePieceToo = false, input.makerName, record)
            }
        } else {
            val placed = problems.problems.withIndex().groupBy { it.value.defaultPart }
            // Every part with a problem on it or a worker named on it, in the picker's order.
            for (part in WorkPart.entries) {
                val onPart = placed[part].orEmpty()
                if (onPart.isEmpty() && part !in partsNamed) continue
                blocks += HeadingBlock(pdf.part(part), level = 3)
                blocks += namesBlock(pdf.workedOnThisPart, chosen, part, wholePieceToo = false, input.makerName, record)
                blocks += partProblems(chosen, part, labels, record)
            }
            placed[null]?.let { unplaced ->
                blocks += HeadingBlock(pdf.partNotGiven, level = 3)
                blocks += problemBlocks(chosen, unplaced.map { it.index }, labels, forCustomer = false, record = record)
            }
            if (problems.problems.isEmpty() && chosen.verdict != null) blocks += TextBlock(pdf.noProblems)
        }
        blocks += closing(input, labels)
        return content(input, input.kind, listOf(chosen), blocks, labels, record)
    }

    // ------------------------------------------------------------ shared pieces

    /** What every printed problem and name adds to the row: references, snapshot rows, names. */
    private class Record {
        val refs = mutableListOf<String>()
        val problems = mutableListOf<SnapshotProblem>()
        val workers = mutableListOf<SnapshotWorker>()
        val names = linkedSetOf<String>()
    }

    /** The title, the piece, who made it, the date, and the chosen assessment's opening with its photos. */
    private fun opening(input: ReportInput, kind: ReportKind, chosen: ReportAssessment, labels: ReportLabels): List<ReportBlock> {
        val pdf = labels.pdf
        val blocks = mutableListOf<ReportBlock>()
        blocks += HeadingBlock(pdf.title(kind), level = 1)
        blocks += LineBlock(pdf.piece, pieceName(input.piece, labels))
        input.makerName?.let { blocks += TextBlock(pdf.madeBy(it)) }
        blocks += TextBlock(pdf.madeOn(day(input.madeAt)))
        blocks += HeadingBlock(assessmentHeading(chosen, labels), level = 2)
        blocks += verdictLine(chosen, labels)
        // The summary is written for the maker, so these kinds print it; the customer's does not.
        chosen.verdict?.summary?.trim()?.takeIf { it.isNotEmpty() }?.let { blocks += LineBlock(pdf.summary, it) }
        PhotoPlan.of(listOf(chosen.photos)).forAssessment(0).takeIf { it.isNotEmpty() }?.let { blocks += PhotosBlock(it) }
        return blocks
    }

    private fun closing(input: ReportInput, labels: ReportLabels): List<ReportBlock> = buildList {
        input.makerName?.let { add(TextBlock(labels.pdf.madeBy(it))) }
        add(TextBlock(labels.pdf.madeWith))
    }

    private fun content(
        input: ReportInput,
        kind: ReportKind,
        covered: List<ReportAssessment>,
        blocks: List<ReportBlock>,
        labels: ReportLabels,
        record: Record,
    ): ReportContent = ReportContent(
        kind = kind,
        pieceName = pieceName(input.piece, labels),
        labels = labels,
        blocks = blocks,
        sessionIds = covered.map { it.sessionId },
        defectRefs = record.refs,
        snapshot = ReportSnapshot(
            assessments = covered.map { it.snapshot() },
            problems = record.problems,
            workers = record.workers,
            madeBy = input.makerName,
        ),
        printedNames = (record.names + listOfNotNull(input.makerName)).distinct(),
        parts = input.parts,
    )

    /**
     * The names recorded on [part] of [assessment] as a block, or nothing when none is
     * recorded. A null [part] is the whole piece. [wholePieceToo] adds the whole-piece
     * workers to a part's line: a part report that covers every part of the piece prints
     * them, one that covers some parts does not. The manager's own row (no roster entry)
     * prints the account's name. A roster entry whose name was cleared prints nothing.
     */
    private fun namesBlock(
        label: String,
        assessment: ReportAssessment,
        part: WorkPart?,
        wholePieceToo: Boolean,
        makerName: String?,
        record: Record,
    ): List<ReportBlock> {
        val rows = assessment.workers.filter { it.part == part || (wholePieceToo && it.part == null) }
        val names = mutableListOf<String>()
        for (row in rows) {
            val name = row.name ?: (if (row.workerId == null) makerName else null) ?: continue
            // Each row recorded once, even when a whole-piece worker is printed under
            // several parts; a name printed once per line, even when two roster entries
            // share it.
            val snapshotRow = SnapshotWorker(assessment.sessionId, row.part?.id, row.workerId, name)
            if (snapshotRow !in record.workers) record.workers += snapshotRow
            record.names += name
            if (name !in names) names += name
        }
        return if (names.isEmpty()) emptyList() else listOf(NamesBlock(label, names))
    }

    /** The problems of [assessment] on [part], or the one line that says there are none. */
    private fun partProblems(assessment: ReportAssessment, part: WorkPart, labels: ReportLabels, record: Record): List<ReportBlock> {
        val list = assessment.problems
        if (list.unknown) return listOf(TextBlock(labels.pdf.problemsUnknown))
        val onPart = list.problems.withIndex().filter { it.value.defaultPart == part }.map { it.index }
        // "No issues recorded" only under a verdict: an assessment without one has already
        // said "No full verdict", and the record line would read as cleared.
        if (onPart.isEmpty()) return if (assessment.verdict != null) listOf(TextBlock(labels.pdf.noIssuesOnPart)) else emptyList()
        return problemBlocks(assessment, onPart, labels, forCustomer = false, record = record)
    }

    /** True when the chosen parts cover every part the piece's type offers, so whole-piece workers belong on them. */
    private fun coversEveryPart(input: ReportInput): Boolean {
        val type = ItemType.fromId(input.piece.itemTypeId) ?: ItemType.OTHER
        return input.parts.containsAll(WorkerParts.partsFor(type))
    }

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
     * The problems of [assessment] at [ordinals] as blocks, recorded as printed, or the one
     * line that says why there are none. For a customer, what to do is left out and no
     * part is printed.
     */
    private fun problemBlocks(
        assessment: ReportAssessment,
        ordinals: List<Int>,
        labels: ReportLabels,
        forCustomer: Boolean,
        record: Record,
    ): List<ReportBlock> {
        val list = assessment.problems
        if (list.unknown) return listOf(TextBlock(labels.pdf.problemsUnknown))
        if (ordinals.isEmpty()) {
            // Only an assessment with a verdict says "nothing recorded": one without has
            // already said "No full verdict", and a second line would overstate it.
            return if (assessment.verdict != null) listOf(TextBlock(labels.pdf.noProblems)) else emptyList()
        }
        return ordinals.map { ordinal ->
            val problem = list.problems[ordinal]
            val part = if (forCustomer) null else problem.defaultPart
            record.refs += "${assessment.sourceMessageId}:$ordinal"
            record.problems += SnapshotProblem(
                sessionId = assessment.sessionId,
                messageId = assessment.sourceMessageId,
                ordinal = ordinal,
                title = problem.title,
                severity = problem.severity,
                part = part?.id,
            )
            ProblemBlock(
                title = problem.title,
                severity = labels.severity(Severity.fromId(problem.severity)),
                part = part?.let { labels.pdf.part(it) },
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
