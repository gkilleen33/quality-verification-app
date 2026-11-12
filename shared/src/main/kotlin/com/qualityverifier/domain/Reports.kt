package com.qualityverifier.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The kinds of PDF report Fundi Bora can send about a piece, chosen by "Who is this for?"
 * (plan v7 §5.2; Nathan, 12 November 2026).
 *
 * A closed list, mirrored by the CHECK on `reports.kind` (V32) and held to it by
 * FundiVocabularyTest. The kind is what decides which verdict fields are printed and
 * whether worker names are: a customer sees what describes the piece and nothing addressed
 * to the maker; a worker or the manager sees everything, names included.
 */
enum class ReportKind(val id: String) {
    /** Every assessment of the piece, for the buyer. No names, no parts, nothing for the maker. */
    CUSTOMER("customer"),

    /** The chosen assessment's problems on the chosen parts, for the worker who did them. */
    PART_ISSUES("part_issues"),

    /** On a re-assessment: last time's and this time's problems on the chosen parts. */
    PART_FIXES("part_fixes"),

    /** Every problem of the chosen assessment, grouped by part, for the manager's own use. */
    FULL("full"),

    /** The full report, sent by a worker to a studio they are linked to. */
    TO_MANAGER("to_manager"),

    /** One linked worker's work across pieces, from the worker's page. */
    WORKER_WORK("worker_work");

    /** The two kinds that name one or more parts of the piece. */
    val takesParts: Boolean get() = this == PART_ISSUES || this == PART_FIXES

    /** The kinds that cover one chosen assessment rather than every assessment of the piece. */
    val coversOneAssessment: Boolean
        get() = this == PART_ISSUES || this == PART_FIXES || this == FULL || this == TO_MANAGER

    /** Every kind but the customer's prints who made it and the names of the workers. */
    val isInternal: Boolean get() = this != CUSTOMER

    companion object {
        fun fromId(id: String): ReportKind? =
            entries.firstOrNull { it.id == id.trim().lowercase() }
    }
}

/**
 * What the phone sends to `POST /v1/fundi/pieces/{id}/reports` and to the preview route.
 *
 * [parts] only for the two part kinds; [sessionId] for every kind but the customer's, which
 * always covers every assessment. The server refuses anything else with 400.
 */
@Serializable
data class ReportRequest(
    val kind: String,
    val parts: List<String>? = null,
    @SerialName("session_id") val sessionId: String? = null,
)

/**
 * What a report says, as a list of blocks, before it is laid out as a PDF.
 *
 * Shared by the server, which composes it and renders it, and by the phone, which shows it
 * as the preview: the preview is the same list the PDF is drawn from, so the two cannot
 * differ (plan v7 §5.7). Photos travel as their hashes; the phone shows their number.
 */
@Serializable
sealed interface ReportBlock

/** A heading. Level 1 opens the report, 2 an assessment or a piece, 3 a part. */
@Serializable
@SerialName("heading")
data class HeadingBlock(val text: String, val level: Int = 2) : ReportBlock

/** A line of running text. */
@Serializable
@SerialName("text")
data class TextBlock(val text: String) : ReportBlock

/** A labelled line: "Verdict: Fair — Strong table, one joint to close". */
@Serializable
@SerialName("line")
data class LineBlock(val label: String, val value: String) : ReportBlock

/** The names under a part heading: "Worked on this part: Kamau, Otieno". */
@Serializable
@SerialName("names")
data class NamesBlock(val label: String, val names: List<String>) : ReportBlock

/**
 * One problem. [whatToDo] is null on a customer report, where nothing addressed to the
 * maker is printed; [part] is null where no part is printed (the customer report) or the
 * problem has none.
 */
@Serializable
@SerialName("problem")
data class ProblemBlock(
    val title: String,
    val severity: String,
    val part: String? = null,
    @SerialName("what_i_see") val whatISee: String = "",
    @SerialName("what_it_means") val whatItMeans: String = "",
    @SerialName("what_to_do") val whatToDo: String? = null,
) : ReportBlock

/** The photos printed at this point, by hash, in order. */
@Serializable
@SerialName("photos")
data class PhotosBlock(val sha256: List<String>) : ReportBlock

/** Small print: a rule the reader should know, such as what a missing problem means. */
@Serializable
@SerialName("note")
data class NoteBlock(val text: String) : ReportBlock

/**
 * The preview the server answers with, and the phone shows before "Send": the blocks the
 * PDF will be drawn from, with the assessments it covers and how many photos it carries.
 */
@Serializable
data class ReportPreview(
    val kind: String,
    /** The piece's name as the report prints it: its label, else the kind of thing it is. */
    @SerialName("piece_name") val pieceName: String,
    /** The language of the report's own lines (`en`, `sw`), so the phone's headings match the PDF's. */
    val language: String = "en",
    val blocks: List<ReportBlock>,
    @SerialName("session_ids") val sessionIds: List<String>,
    @SerialName("photo_count") val photoCount: Int,
)
