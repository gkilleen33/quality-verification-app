package com.qualityverifier.text

import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.WorkPart

/**
 * The fixed wording of the PDF reports (plan v7 §5), in the language of the assessment,
 * beside [ReportLabels] whose verdict words, severities and three problem headings the
 * reports reuse.
 *
 * Every line a report prints that is not the model's own text comes from here, so the PR
 * that adds a line lists it, and a translator has one place to look.
 *
 * TRANSLATION STATUS: the Swahili below is unreviewed, like the rest of [ReportLabels], and
 * needs a native speaker before any report leaves a pilot phone in Swahili. The lines that
 * name a person's work ("Worked on this part") need that pass most: they sit beside a
 * problem, and the tone of the word carries as much as its meaning.
 */
data class ReportPdfLabels(
    /** The report's title, by kind. The worker-work title takes the worker's name. */
    private val titles: Map<ReportKind, String>,
    /** "Piece", before the piece's name. */
    val piece: String,
    /** "Report made by {name}", on every report but the customer's. */
    private val madeByFormat: String,
    /** "Made on {date}". */
    private val madeOnFormat: String,
    /** The last line of every report. */
    val madeWith: String,
    /** "Assessment of {date}", the heading of each assessment. */
    private val assessmentOfFormat: String,
    /** After the date of an assessment that never reached a verdict and has one turn. */
    val notFinished: String,
    /** "Verdict", before the level and headline. */
    val verdict: String,
    /** For an assessment with no verdict: unfinished, or a check that issues none. */
    val noFullVerdict: String,
    /** "Summary", before the verdict's summary. Internal reports only. */
    val summary: String,
    /** Closes the customer report with the newest verdict. */
    val latestVerdict: String,
    /** An assessment with a verdict and nothing wrong. */
    val noProblems: String,
    /** The newest diagnosis would not decode: not known, which is not the same as none. */
    val problemsUnknown: String,
    /** Under a part heading, before the names recorded on that part. */
    val workedOnThisPart: String,
    /** The same line on a fixes report, where the names are the re-assessment's. */
    val workedOnThisPartThisAssessment: String,
    /** On a full report, above the first part: the workers recorded on the whole piece. */
    val alsoWorkedOn: String,
    /** The heading for problems the server could not put down to a part. */
    val partNotGiven: String,
    /** A chosen part with no problem on the assessment. A record, not a judgement. */
    val noIssuesOnPart: String,
    /** "Found in the assessment of {date}": last time's problems on a fixes report. */
    private val foundInAssessmentOfFormat: String,
    /** This time's problems on a fixes report. */
    val foundThisTime: String,
    /** Why a fixes report never marks a problem fixed. */
    val missingMayBeFixed: String,
    /** On a worker-work report, the label before "Whole piece" (the renderer adds the colon). */
    val workedOn: String,
    /** On a worker-work report, a piece the worker is recorded on as a whole. */
    val wholePiece: String,
    /** The parts of a piece as the reports name them. */
    private val partNames: Map<WorkPart, String>,
    /**
     * On a worker-work report (v0.6.0, row 22), under an assessment the worker made on their
     * own phone, of a piece they labelled for the studio.
     */
    val theirOwnAssessment: String,
    /** On a customer report (v0.6.0, row 27), under the final assessment of the finished piece. */
    val finalCheck: String,
    /**
     * Kagua's PDF from a shop's tablet (v0.7.0, row 31), which the customer downloads by QR
     * code: its title, the label before the item, the heading over the problems, the heading
     * over what the check could not see, and the line that says whose check it is. The two
     * headings use the words Kagua's shared report already uses ("What to look at:", "Not
     * checked:"), so a term means one thing in both.
     */
    val kaguaTitle: String,
    val item: String,
    val whatToLookAt: String,
    val notChecked: String,
    val independentCheck: String,
    /**
     * The page a report link answers with once it no longer works, or when it never did
     * (row 31): one answer for a wrong token, a used-up one and an expired one, so the page
     * says nothing about which. Shown in English and Kiswahili together, because a wrong
     * token has no assessment and so no language.
     */
    val linkExpiredTitle: String,
    val linkExpiredBody: String,
    /** The same page's answer when one address has asked too often (the route's rate limit). */
    val linkBusy: String,
) {
    fun title(kind: ReportKind, workerName: String? = null): String =
        titles.getValue(kind).replace("{name}", workerName.orEmpty())

    fun madeBy(name: String): String = madeByFormat.replace("{name}", name)

    fun madeOn(date: String): String = madeOnFormat.replace("{date}", date)

    fun assessmentOf(date: String): String = assessmentOfFormat.replace("{date}", date)

    fun foundInAssessmentOf(date: String): String = foundInAssessmentOfFormat.replace("{date}", date)

    fun part(part: WorkPart): String = partNames.getValue(part)

    companion object {
        val ENGLISH = ReportPdfLabels(
            titles = mapOf(
                ReportKind.CUSTOMER to "Quality report",
                ReportKind.PART_ISSUES to "Report on the work",
                ReportKind.PART_FIXES to "Report on the fixes",
                ReportKind.FULL to "Full report",
                ReportKind.TO_MANAGER to "Report for my manager",
                ReportKind.WORKER_WORK to "Report on the work of {name}",
            ),
            piece = "Piece",
            madeByFormat = "Report made by {name}",
            madeOnFormat = "Made on {date}",
            madeWith = "Made with Fundi Bora",
            assessmentOfFormat = "Assessment of {date}",
            notFinished = "Not finished",
            verdict = "Verdict",
            noFullVerdict = "No full verdict",
            summary = "Summary",
            latestVerdict = "Latest verdict",
            noProblems = "No problems recorded in this assessment",
            problemsUnknown = "The problems of this assessment could not be read",
            workedOnThisPart = "Worked on this part:",
            workedOnThisPartThisAssessment = "Worked on this part (this assessment):",
            alsoWorkedOn = "Also worked on this piece:",
            partNotGiven = "Part not given",
            noIssuesOnPart = "No issues recorded on this part in this assessment",
            foundInAssessmentOfFormat = "Found in the assessment of {date}",
            foundThisTime = "Found this time",
            missingMayBeFixed = "An issue missing from this assessment may have been put right, " +
                "or may not have been photographed.",
            workedOn = "Worked on",
            wholePiece = "Whole piece",
            partNames = mapOf(
                WorkPart.FRAME to "Frame",
                WorkPart.JOINERY to "Joinery",
                WorkPart.UPHOLSTERY to "Upholstery",
                WorkPart.FINISHING to "Finishing",
                WorkPart.ASSEMBLY to "Assembly",
                WorkPart.OTHER to "Other",
            ),
            theirOwnAssessment = "Their own assessment, made on their phone",
            finalCheck = "Final check of the finished piece",
            kaguaTitle = "Kagua report",
            item = "Item",
            whatToLookAt = "What to look at",
            notChecked = "Not checked",
            independentCheck = "This is an independent check by Kagua.",
            linkExpiredTitle = "This link has expired",
            linkExpiredBody = "A Kagua report link works for 30 minutes, and for up to three downloads.",
            linkBusy = "Too many tries from this network. Wait a few minutes and try again.",
        )

        // REVIEW: unreviewed Swahili throughout; see the class comment.
        val SWAHILI = ReportPdfLabels(
            titles = mapOf(
                ReportKind.CUSTOMER to "Ripoti ya ubora",
                ReportKind.PART_ISSUES to "Ripoti ya kazi",
                ReportKind.PART_FIXES to "Ripoti ya marekebisho",
                ReportKind.FULL to "Ripoti kamili",
                ReportKind.TO_MANAGER to "Ripoti kwa meneja wangu",
                ReportKind.WORKER_WORK to "Ripoti ya kazi ya {name}",
            ),
            piece = "Kipande",
            madeByFormat = "Ripoti imetayarishwa na {name}",
            madeOnFormat = "Imetayarishwa tarehe {date}",
            madeWith = "Imetayarishwa kwa Fundi Bora",
            assessmentOfFormat = "Ukaguzi wa {date}",
            notFinished = "Haujakamilika",
            verdict = "Uamuzi",
            noFullVerdict = "Hakuna uamuzi kamili",
            summary = "Muhtasari",
            latestVerdict = "Uamuzi wa hivi karibuni",
            noProblems = "Hakuna matatizo yaliyorekodiwa katika ukaguzi huu",
            problemsUnknown = "Matatizo ya ukaguzi huu hayakuweza kusomwa",
            workedOnThisPart = "Aliyefanya sehemu hii:",
            workedOnThisPartThisAssessment = "Aliyefanya sehemu hii (ukaguzi huu):",
            alsoWorkedOn = "Pia walifanya kazi kwenye kipande hiki:",
            partNotGiven = "Sehemu haijatajwa",
            noIssuesOnPart = "Hakuna matatizo yaliyorekodiwa kwenye sehemu hii katika ukaguzi huu",
            foundInAssessmentOfFormat = "Yaliyoonekana katika ukaguzi wa {date}",
            foundThisTime = "Yaliyoonekana safari hii",
            missingMayBeFixed = "Tatizo lisilokuwepo katika ukaguzi huu huenda limerekebishwa, " +
                "au huenda halikupigwa picha.",
            workedOn = "Alifanya",
            wholePiece = "Kipande kizima",
            partNames = mapOf(
                WorkPart.FRAME to "Fremu",
                WorkPart.JOINERY to "Viungo",
                WorkPart.UPHOLSTERY to "Sponji na kitambaa",
                WorkPart.FINISHING to "Umaliziaji",
                WorkPart.ASSEMBLY to "Kuunganisha",
                WorkPart.OTHER to "Nyingine",
            ),
            theirOwnAssessment = "Ukaguzi wake mwenyewe, uliofanywa kwenye simu yake",
            finalCheck = "Ukaguzi wa mwisho wa kipande kilichokamilika",
            // Row 31. Not yet reviewed by a Kiswahili speaker. The two headings repeat
            // ReportLabels.SWAHILI's shareWhatToLookAt and shareNotChecked.
            kaguaTitle = "Ripoti ya Kagua",
            item = "Kitu",
            whatToLookAt = "Ya kuangalia",
            notChecked = "Hayakuthibitishwa",
            independentCheck = "Huu ni ukaguzi huru uliofanywa na Kagua.",
            linkExpiredTitle = "Kiungo hiki kimeisha muda wake",
            linkExpiredBody = "Kiungo cha ripoti ya Kagua kinafanya kazi kwa dakika 30, na kwa upakuaji hadi mara tatu.",
            linkBusy = "Majaribio mengi mno kutoka mtandao huu. Subiri dakika chache kisha ujaribu tena.",
        )
    }
}
