package com.qualityverifier.fundi.ui.home

import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.SessionSummary
import com.qualityverifier.text.FundiScreenLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One physical piece as Home, "All my pieces", a worker's page and the piece's own page list
 * it (v0.7.1, rows 44, 45 and 56): its assessments, newest first, and what the newest found.
 */
data class PieceSummary(
    val pieceId: String,
    /** The piece's newest assessment, which names the piece and opens from its row. */
    val latest: SessionSummary,
    /** Every assessment of the piece on this phone, newest first. */
    val assessments: List<PieceAssessment>,
    /** When the piece's first assessment was started, for "New" (row 56). */
    val firstStartedAt: Long,
) {
    val newest: PieceAssessment get() = assessments.first()
}

/** One assessment of a piece, with what its line needs. */
data class PieceAssessment(
    val summary: SessionSummary,
    /** The findings in its reply: improvements in prompt E, problems before it. Null when not known. */
    val improvements: Int?,
    /** Why it was made, from the intake code; null when not recorded. */
    val purpose: FundiPurpose?,
)

/** In progress or finished, as "All my pieces" groups a piece and its Status row reads (rows 55, 56). */
enum class PieceStage(val id: String) {
    IN_PROGRESS("in_progress"),
    FINISHED("finished");

    companion object {
        fun fromId(id: String?): PieceStage? = entries.firstOrNull { it.id == id }

        /**
         * The stage an assessment's purpose implies: checking work in progress means the
         * piece is being made; any other purpose means it is finished (Nathan, 24 November
         * 2026). Null when the purpose is not known, which reads as finished on the page.
         */
        fun impliedBy(purpose: FundiPurpose?): PieceStage =
            if (purpose == FundiPurpose.WORK_IN_PROGRESS) IN_PROGRESS else FINISHED
    }
}

/**
 * The words and the rules of the piece lists, kept pure so they can be tested without a
 * screen. English only, as Fundi Bora's chrome is (issue #20).
 */
object PieceLines {

    /** Home shows the newest three; "All my pieces (N)" opens the rest (row 56). */
    const val HOME_COUNT = 3

    /** How long a piece shows "New" after its first assessment (row 56). */
    const val NEW_MILLIS = 24L * 60 * 60 * 1000

    /** "4 improvements · checked 20 Nov" (row 45, option A); "1 improvement" in the singular. */
    fun line(improvements: Int?, checkedAt: Long): String {
        val checked = "checked " + day(checkedAt)
        return when (improvements) {
            null -> checked.replaceFirstChar { it.uppercase() }
            1 -> "1 improvement · $checked"
            else -> "$improvements improvements · $checked"
        }
    }

    /**
     * An assessment's line. One with no reply yet, which somebody started and did not finish
     * (a phone call mid-plan, usually), says so, as the piece card did, rather than "0
     * improvements".
     */
    fun line(assessment: PieceAssessment): String =
        if (assessment.summary.messageCount <= 1) "Not finished · started " + day(assessment.summary.createdAt)
        else line(assessment.improvements, assessment.summary.updatedAt)

    /**
     * "Assess your first piece" once the list has loaded and is empty (P20), else "Assess a
     * new piece". Never "first" while the pieces are still being read.
     */
    fun assessLabel(words: FundiScreenLabels, loaded: Boolean, pieces: Int): String =
        if (loaded && pieces == 0) words.assessFirstPiece else words.assessNewPiece

    /** Whether Home adds the row "All my pieces (N)": only when there are more than it lists. */
    fun showsAll(pieces: Int): Boolean = pieces > HOME_COUNT

    /** Whether the piece's first assessment is under a day old, so its row shows "New". */
    fun isNew(piece: PieceSummary, now: Long): Boolean = now - piece.firstStartedAt in 0 until NEW_MILLIS

    /** Groups [summaries] into pieces, newest first, reading each assessment's findings and purpose. */
    suspend fun piecesOf(
        summaries: List<SessionSummary>,
        assessmentOf: suspend (SessionSummary) -> PieceAssessment,
    ): List<PieceSummary> =
        summaries.groupBy { it.piece }.map { (pieceId, sessions) ->
            val newestFirst = sessions.sortedByDescending { it.updatedAt }
            PieceSummary(
                pieceId = pieceId,
                latest = newestFirst.first(),
                assessments = newestFirst.map { assessmentOf(it) },
                firstStartedAt = sessions.minOf { it.createdAt },
            )
        }.sortedByDescending { it.latest.updatedAt }

    /** "3 workers · 1 linked", or "No workers yet", for Home's "My workers" row. */
    fun workersLine(active: Int, linked: Int): String =
        if (active == 0) "No workers yet" else "${count(active, "worker")} · $linked linked"

    /** "1 studio", "2 studios", or "Only your own workshop", for "Studios I work for". */
    fun studiosLine(active: Int): String = if (active == 0) "Only your own workshop" else count(active, "studio")

    /** "Employee · 3 pieces" for a row of "My workers". */
    fun workerRowLine(kind: String, pieces: Int): String = "$kind · ${count(pieces, "piece")}"

    /**
     * What New piece still needs, or null when both are chosen (row 46, P19): the line under
     * the greyed "Start: take the photos".
     */
    fun newPieceMissing(typeChosen: Boolean, studioChosen: Boolean): String? = when {
        !typeChosen && !studioChosen -> "Choose what you're working on and who it's for."
        !typeChosen -> "Choose what you're working on."
        !studioChosen -> "Choose who it's for."
        else -> null
    }

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** "20 Nov", in Nairobi, like every other date in this project. */
    fun day(at: Long): String = format("d MMM", at)

    /** "10:14", on the phone's own clock: the time the fundi saw. */
    fun time(at: Long): String =
        SimpleDateFormat("HH:mm", Locale.UK).format(Date(at))

    private fun format(pattern: String, at: Long): String =
        SimpleDateFormat(pattern, Locale.UK).apply { timeZone = TimeZone.getTimeZone("Africa/Nairobi") }.format(Date(at))
}
