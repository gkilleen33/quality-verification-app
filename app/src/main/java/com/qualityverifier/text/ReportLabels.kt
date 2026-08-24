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
    /**
     * Answered, but with nothing learned. Distinct from [cannotDoThis]: having tried and
     * been unsure is not the same as never having tried, and neither is a failure.
     */
    val notSure: String,
    val inThisInspection: String,
    // The local intake, before anything is sent.
    val intakeUpholsteryQuestion: String,
    val intakeUpholsteryYes: String,
    val intakeUpholsteryNo: String,
    val intakeOwnershipQuestion: String,
    val intakeBuying: String,
    val intakeAlreadyOwn: String,
    val intakePriceQuestion: String,
    val intakePriceHint: String,
    val intakePriceSkip: String,
    val intakeUsageQuestion: String,
    val intakeUsageDaily: String,
    val intakeUsageOccasional: String,
    val intakeUsageBusiness: String,
    val intakeStart: String,
    val intakeNext: String,
    val intakeDepthQuestion: String,
    val intakeDepthFull: String,
    val intakeDepthFullDetail: String,
    val intakeDepthRapid: String,
    val intakeDepthRapidDetail: String,
    val intakeSomethingElse: String,
    val openingShotInstruction: String,
    // The opening turn the intake writes on the customer's behalf.
    val intakeSaysBuying: String,
    val intakeSaysAlreadyOwn: String,
    val intakeSaysPriceUnknown: String,
    val intakeSaysDaily: String,
    val intakeSaysOccasional: String,
    val intakeSaysBusiness: String,
    val intakeSaysUseLanguage: String,
    val intakeSaysFull: String,
    val intakeSaysRapid: String,
    val intakeSaysTakeOver: String,
    private val intakeSaysPriceFormat: String,
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

    fun intakeSaysPrice(price: String): String =
        intakeSaysPriceFormat.replace("{price}", price)

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

    /**
     * What to call the piece before the intake has settled which protocol applies.
     *
     * "Wooden chair" as the heading over the question "does this chair have cushioning?"
     * answers the question it is asking. The grid's own label is the neutral one.
     */
    fun neutralItemName(itemType: ItemType): String = when (code) {
        SWAHILI_CODE -> itemType.swahiliName ?: itemType.homeLabel
        else -> itemType.homeLabel
    }

    companion object {
        private const val SWAHILI_CODE = "sw"

        val ENGLISH = ReportLabels(
            code = "en",
            verdictHeading = "VERDICT",
            couldNotVerifyHeading = "COULDN'T VERIFY",
            whatISeeHeading = "WHAT I SEE",
            whatItMeansHeading = "WHAT IT MEANS FOR YOU",
            whatToDoHeading = "THE FIX",
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
            notSure = "I'm not sure",
            inThisInspection = "In this inspection",
            intakeUpholsteryQuestion = "Does this chair have cushioning, or is it bare wood?",
            intakeUpholsteryYes = "It has cushioning or fabric",
            intakeUpholsteryNo = "Bare wood, no padding",
            intakeOwnershipQuestion = "Are you buying this, or checking one you already own?",
            intakeBuying = "I'm buying it",
            intakeAlreadyOwn = "I already own it",
            intakePriceQuestion = "What price has the seller quoted?",
            intakePriceHint = "Price",
            intakePriceSkip = "I don't know",
            intakeUsageQuestion = "What will it be used for?",
            intakeUsageDaily = "Heavy daily use",
            intakeUsageOccasional = "Occasional use",
            intakeUsageBusiness = "A business, like a restaurant or a hostel",
            intakeStart = "Start the assessment",
            intakeNext = "Next",
            intakeDepthQuestion = "How thorough should this be?",
            intakeDepthFull = "Full assessment",
            intakeDepthFullDetail = "Guided photos and a few hands-on tests, a few minutes. Recommended.",
            intakeDepthRapid = "Rapid assessment",
            intakeDepthRapidDetail = "Two photos and a quick opinion. Likelier to miss something.",
            intakeSomethingElse = "Something else — let me explain",
            openingShotInstruction = "One photo of the whole thing to start. Stand back far " +
                "enough that all of it is in the frame, including where it meets the floor. " +
                "This lets me see what I am dealing with before asking for close-ups.",
            intakeSaysBuying = "I am buying this.",
            intakeSaysAlreadyOwn = "I already own this one.",
            intakeSaysPriceUnknown = "I do not know what price is being asked.",
            intakeSaysDaily = "It will get heavy daily use.",
            intakeSaysOccasional = "It will only be used occasionally.",
            intakeSaysBusiness = "It is for a business.",
            intakeSaysUseLanguage = "Please answer me in English.",
            intakeSaysFull = "I would like the full assessment.",
            intakeSaysRapid = "I would like a rapid assessment.",
            intakeSaysTakeOver = "I could not answer the rest with the buttons, so please ask me yourself.",
            intakeSaysPriceFormat = "The seller is asking {price}.",
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
            whatToDoHeading = "MATENGENEZO",
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
            notSure = "Sina uhakika",
            inThisInspection = "Katika ukaguzi huu",
            intakeUpholsteryQuestion = "Kiti hiki kina sponji, au ni mbao tupu?",
            intakeUpholsteryYes = "Kina sponji au kitambaa",
            intakeUpholsteryNo = "Mbao tupu, hakuna sponji",
            intakeOwnershipQuestion = "Unanunua hii, au unakagua uliyo nayo?",
            intakeBuying = "Ninanunua",
            intakeAlreadyOwn = "Ninayo tayari",
            intakePriceQuestion = "Muuzaji anataka bei gani?",
            intakePriceHint = "Bei",
            intakePriceSkip = "Sijui",
            intakeUsageQuestion = "Itatumika kwa nini?",
            intakeUsageDaily = "Matumizi mazito ya kila siku",
            intakeUsageOccasional = "Matumizi ya mara kwa mara",
            intakeUsageBusiness = "Biashara, kama mkahawa au hosteli",
            intakeStart = "Anza ukaguzi",
            intakeNext = "Endelea",
            intakeDepthQuestion = "Ukaguzi uwe wa kina kiasi gani?",
            intakeDepthFull = "Ukaguzi kamili",
            intakeDepthFullDetail = "Picha kwa maelekezo na majaribio kadhaa, dakika chache. Inapendekezwa.",
            intakeDepthRapid = "Ukaguzi wa haraka",
            intakeDepthRapidDetail = "Picha mbili na maoni ya haraka. Ni rahisi kukosa kitu.",
            intakeSomethingElse = "Kitu kingine — niambie mwenyewe",
            openingShotInstruction = "Picha moja ya kitu kizima kwa kuanzia. Simama mbali " +
                "kiasi ili kiwe chote kwenye picha, pamoja na pale kinapogusa sakafu. " +
                "Hii inanisaidia kuona ninachoshughulika nacho kabla ya kuomba picha za karibu.",
            intakeSaysBuying = "Ninanunua hii.",
            intakeSaysAlreadyOwn = "Hii ninayo tayari.",
            intakeSaysPriceUnknown = "Sijui bei inayotakiwa.",
            intakeSaysDaily = "Itatumika kwa nguvu kila siku.",
            intakeSaysOccasional = "Itatumika mara kwa mara tu.",
            intakeSaysBusiness = "Ni kwa biashara.",
            intakeSaysUseLanguage = "Tafadhali nijibu kwa Kiswahili.",
            intakeSaysFull = "Nataka ukaguzi kamili.",
            intakeSaysRapid = "Nataka ukaguzi wa haraka.",
            intakeSaysTakeOver = "Sikuweza kujibu mengine kwa vitufe, tafadhali niulize wewe mwenyewe.",
            intakeSaysPriceFormat = "Muuzaji anataka {price}.",
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
