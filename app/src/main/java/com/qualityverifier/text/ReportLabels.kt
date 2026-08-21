package com.qualityverifier.text

import com.qualityverifier.domain.ItemType
import com.qualityverifier.domain.Severity
import com.qualityverifier.domain.VerdictLevel

/**
 * The fixed wording around a verdict: card headings, severity words, the three verdict
 * levels, and the share message.
 *
 * These follow the **language of the assessment**, not the language of the phone. The
 * assistant mirrors whatever the customer writes, so a Swahili conversation on a
 * handset set to English produced Swahili findings under English headings — which reads
 * as a half-finished app. The verdict carries its own language for exactly this reason.
 *
 * TRANSLATION STATUS: the Swahili below is unreviewed. It needs a native speaker before
 * any pilot, and the three verdict levels need it most — the design brief calls those
 * out by name, because a verdict is a judgement somebody acts on and the tone of the
 * word carries as much as its meaning. Treat [SWAHILI] as a placeholder that is better
 * than English, not as finished copy.
 */
data class ReportLabels(
    val code: String,
    val verdictHeading: String,
    val couldNotVerifyHeading: String,
    val whatISeeHeading: String,
    val whatItMeansHeading: String,
    val whatToDoHeading: String,
    val sayToSellerHeading: String,
    val askAboutThis: String,
    val inProgress: String,
    private val levels: Map<VerdictLevel, String>,
    private val severities: Map<Severity, String>,
    private val areas: Map<String, String>,
    val shareHeader: String,
    val shareVerdict: String,
    val shareWhatToLookAt: String,
    val shareNotChecked: String,
    val shareSignOff: String,
    // Chrome for the collection run: the plan card, the test screens, the wait.
    val photosHeading: String,
    val testsHeading: String,
    val startCamera: String,
    val continueToTests: String,
    val sendForInspection: String,
    val retake: String,
    val inspecting: String,
    val stagePreparing: String,
    val stageSending: String,
    val stageExamining: String,
    val seeVerdict: String,
    val submissionTestsHeading: String,
    /** Said of a step the customer could not do — never left silent. */
    val notDone: String,
    val cannotDoThis: String,
    val inThisInspection: String,
    private val photoCountFormat: String,
    private val testResultCountFormat: String,
    private val sentFormat: String,
    private val shotOfFormat: String,
    private val testOfFormat: String,
    private val photosTakenFormat: String,
) {
    /** "Shot 3 of 6", above the viewfinder. */
    fun shotOf(index: Int, total: Int): String =
        shotOfFormat.replace("{n}", index.toString()).replace("{total}", total.toString())

    fun testOf(index: Int, total: Int): String =
        testOfFormat.replace("{n}", index.toString()).replace("{total}", total.toString())

    /** "6 of 6 photos taken", on the plan card and in the submitted turn. */
    fun photosTaken(taken: Int, total: Int): String =
        photosTakenFormat.replace("{n}", taken.toString()).replace("{total}", total.toString())

    fun photoCount(n: Int): String = photoCountFormat.replace("{n}", n.toString())

    fun testResultCount(n: Int): String = testResultCountFormat.replace("{n}", n.toString())

    fun sent(size: String): String = sentFormat.replace("{size}", size)

    fun level(level: VerdictLevel): String = levels[level] ?: levels.getValue(VerdictLevel.UNKNOWN)

    fun severity(severity: Severity): String = severities[severity].orEmpty()

    /** Falls back to the raw value so an area the schema does not list still labels itself. */
    fun area(area: String): String =
        areas[area.trim().lowercase()] ?: area.trim().uppercase()

    /**
     * What to call the piece. Falls back to the English name where no Swahili term has
     * been sourced — see [ItemType.swahiliName] — so this never invents a word.
     */
    fun itemName(itemType: ItemType): String = when (code) {
        SWAHILI_CODE -> itemType.swahiliName ?: itemType.displayName
        else -> itemType.displayName
    }

    companion object {
        private const val SWAHILI_CODE = "sw"

        val ENGLISH = ReportLabels(
            code = "en",
            verdictHeading = "VERDICT",
            couldNotVerifyHeading = "COULDN'T VERIFY",
            whatISeeHeading = "WHAT I SEE",
            whatItMeansHeading = "WHAT IT MEANS FOR YOU",
            whatToDoHeading = "WHAT TO DO",
            sayToSellerHeading = "SAY THIS TO THE SELLER",
            askAboutThis = "Ask about this piece",
            inProgress = "In progress",
            levels = mapOf(
                VerdictLevel.SOUND to "Sound",
                VerdictLevel.FAIR to "Fair",
                VerdictLevel.SERIOUS to "Serious concerns",
                VerdictLevel.UNKNOWN to "Assessment",
            ),
            severities = mapOf(
                Severity.SERIOUS to "Serious",
                Severity.MODERATE to "Moderate",
                Severity.MINOR to "Minor",
                Severity.COSMETIC to "Cosmetic",
                Severity.UNKNOWN to "",
            ),
            areas = mapOf(
                "structural" to "STRUCTURAL",
                "level" to "LEVEL",
                "surface" to "SURFACE",
                "material" to "MATERIAL",
                "upholstery" to "UPHOLSTERY",
                "hardware" to "HARDWARE",
                "other" to "OTHER",
            ),
            shareHeader = "KAGUA REPORT",
            shareVerdict = "VERDICT",
            shareWhatToLookAt = "What to look at:",
            shareNotChecked = "Not checked:",
            shareSignOff = "Checked with Kagua — jua kabla ya kununua.",
            photosHeading = "Photos",
            testsHeading = "Physical tests",
            startCamera = "Start camera",
            continueToTests = "Continue to the tests",
            sendForInspection = "Send for inspection",
            retake = "Retake",
            inspecting = "Inspecting",
            stagePreparing = "Preparing the photos",
            stageSending = "Sending",
            stageExamining = "Looking at the furniture",
            seeVerdict = "See the verdict",
            submissionTestsHeading = "Test results",
            notDone = "not done",
            cannotDoThis = "I can't do this one",
            inThisInspection = "In this inspection",
            photoCountFormat = "{n} photos",
            testResultCountFormat = "{n} test results",
            sentFormat = "{size} sent",
            shotOfFormat = "Shot {n} of {total}",
            testOfFormat = "Test {n} of {total}",
            photosTakenFormat = "{n} of {total} photos taken",
        )

        val SWAHILI = ReportLabels(
            code = "sw",
            verdictHeading = "UAMUZI",
            couldNotVerifyHeading = "SIKUWEZA KUTHIBITISHA",
            whatISeeHeading = "NINACHOKIONA",
            whatItMeansHeading = "MAANA YAKE KWAKO",
            whatToDoHeading = "LA KUFANYA",
            sayToSellerHeading = "MWAMBIE MUUZAJI HIVI",
            askAboutThis = "Uliza kuhusu kipande hiki",
            inProgress = "Inaendelea",
            levels = mapOf(
                VerdictLevel.SOUND to "Imara",
                VerdictLevel.FAIR to "Wastani",
                VerdictLevel.SERIOUS to "Matatizo makubwa",
                VerdictLevel.UNKNOWN to "Ukaguzi",
            ),
            severities = mapOf(
                Severity.SERIOUS to "Kubwa",
                Severity.MODERATE to "Wastani",
                Severity.MINOR to "Ndogo",
                Severity.COSMETIC to "Muonekano",
                Severity.UNKNOWN to "",
            ),
            areas = mapOf(
                "structural" to "MUUNDO",
                "level" to "USAWA",
                "surface" to "USO",
                "material" to "MBAO NA MALI",
                "upholstery" to "SPONJI NA KITAMBAA",
                "hardware" to "VIFAA",
                "other" to "NYINGINE",
            ),
            shareHeader = "RIPOTI YA KAGUA",
            shareVerdict = "UAMUZI",
            shareWhatToLookAt = "Ya kuangalia:",
            shareNotChecked = "Hayakuthibitishwa:",
            shareSignOff = "Imekaguliwa na Kagua — jua kabla ya kununua.",
            photosHeading = "Picha",
            testsHeading = "Majaribio ya mikono",
            startCamera = "Anza kamera",
            continueToTests = "Endelea na majaribio",
            sendForInspection = "Tuma kwa ukaguzi",
            retake = "Piga tena",
            inspecting = "Inakagua",
            stagePreparing = "Inatayarisha picha",
            stageSending = "Inatuma",
            stageExamining = "Inaangalia samani",
            seeVerdict = "Ona uamuzi",
            submissionTestsHeading = "Majibu ya majaribio",
            notDone = "haikufanyika",
            cannotDoThis = "Siwezi kufanya hili",
            inThisInspection = "Katika ukaguzi huu",
            photoCountFormat = "Picha {n}",
            testResultCountFormat = "Majibu {n} ya majaribio",
            sentFormat = "{size} zimetumwa",
            shotOfFormat = "Picha {n} ya {total}",
            testOfFormat = "Jaribio {n} la {total}",
            photosTakenFormat = "Picha {n} kati ya {total} zimepigwa",
        )

        /**
         * Picks a label set for an assessment.
         *
         * [assessmentLanguage] is what the verdict itself declared. [deviceLanguage] is
         * only consulted when the verdict said nothing, which is what an older stored
         * verdict looks like. English is the last resort rather than the default,
         * because a wrong-language heading is more confusing than an English one.
         */
        fun forLanguage(
            assessmentLanguage: String?,
            deviceLanguage: String? = null,
        ): ReportLabels =
            match(assessmentLanguage) ?: match(deviceLanguage) ?: ENGLISH

        private fun match(code: String?): ReportLabels? {
            val normalised = code?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
            // Accepts "sw", "sw-KE", "swa", and the language's own names, since the
            // model is filling this field in and may answer in any of those forms.
            val swahili = normalised.startsWith("sw") ||
                normalised.contains("swahili") ||
                normalised.contains("kiswahili")
            return when {
                swahili -> SWAHILI
                normalised.startsWith("en") || normalised.contains("english") -> ENGLISH
                else -> null
            }
        }
    }
}
