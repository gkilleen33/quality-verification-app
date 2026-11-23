package com.qualityverifier.text

import com.qualityverifier.domain.Employment
import com.qualityverifier.domain.FixHorizon
import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiPurpose
import com.qualityverifier.domain.ReportKind
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership

/**
 * Fundi Bora's own wording.
 *
 * Separate from [ReportLabels] rather than added to it, for the same reason the `fb-*`
 * fences have their own prefix: this is a different audience's vocabulary. Fundi Bora still
 * uses [ReportLabels] — it reuses `qv-verdict` on a re-assessment, and a verdict needs its
 * headings — so the two are used together rather than one replacing the other.
 *
 * TRANSLATION STATUS: English only, and that is a gap rather than a decision. Kagua's
 * Swahili is unreviewed placeholder copy already (issue #20), and a producer-facing app in
 * Kenya needs that pass more than the buyer's app does, not less. Adding a guessed Swahili
 * [FundiLabels] now would make the gap harder to see, not smaller.
 */
data class FundiLabels(
    val code: String,
    /** Opens the maker's context. Their own words about their own workshop. */
    val contextIntro: String,
    /**
     * Which language to answer in, said out loud in the opening turn.
     *
     * Stated rather than inferred, which is the lesson Kagua already learned: left to
     * guesswork the assistant picks one and then will not change it. Fundi Bora skipped
     * this at first, and the first real assessment came back in Kiswahili to a maker
     * whose whole context was written in English — the model had nothing to go on but
     * the fact that furniture makers in Nairobi often speak Kiswahili.
     *
     * It follows whichever labels are in use, so it becomes Kiswahili the day Kiswahili
     * labels exist rather than needing a second change. It is a starting point, not a
     * cage: the prompt follows the maker if they write in something else.
     */
    val contextLanguage: String,
    val contextToolsHave: String,
    val contextToolsNone: String,
    val contextToolsUnknown: String,
    val contextGoal: String,
    private val worksAtFormat: String,
    private val yearsFormat: String,
    private val workersFormat: String,
    private val makesFormat: String,
    private val piecesPerMonthFormat: String,
    private val timberFormat: String,
    private val borrowedFormat: String,
    val toolNames: Map<ToolKind, String>,
    val goalNames: Map<FundiGoal, String>,
    // ---- the setup flow. Three screens, in the order the mockup asks them.
    val setupWorkshopTitle: String,
    val setupWorkshopBlurb: String,
    val setupWorksAt: String,
    // ---- the shop's GPS point: at the shop first, then consent, then the fix
    val setupAtShopQuestion: String,
    val setupAtShopYes: String,
    val setupAtShopNo: String,
    val setupGpsQuestion: String,
    val setupGpsYes: String,
    val setupGpsNo: String,
    val setupLocating: String,
    private val setupLocatedFormat: String,
    val setupLocateFailed: String,
    val setupLocateNoPermission: String,
    val setupLocateRetry: String,
    // ---- a point per assessment. One question, asked once: at setup, or on Home for a
    // maker who never saw setup's. The same words in both places and in Settings, so the
    // switch they find later is recognisably what they answered.
    /** REVIEW CRITICAL: the consent question itself. */
    val assessLocationQuestion: String,
    /** Under the question and under the switch: what it is for and what it does not cover. */
    val assessLocationHint: String,
    val assessLocationYes: String,
    val assessLocationNo: String,
    /** The switch in Settings: the question, said as the setting it became. */
    val assessLocationSwitch: String,
    val setupYears: String,
    val setupWorkers: String,
    val setupMakes: String,
    val setupPiecesPerMonth: String,
    val setupTimber: String,
    val setupToolsTitle: String,
    val setupToolsBlurb: String,
    val setupGoalsTitle: String,
    val setupGoalsBlurb: String,
    val ownershipNames: Map<ToolOwnership, String>,
    val setupNext: String,
    val setupBack: String,
    val setupFinish: String,
    val setupSkip: String,
    val setupSaving: String,
    val setupFailed: String,
    // ---- Settings. The screen is shared with Kagua and worded by AuthLabels; these and
    // the location switch's two above are where Kagua's sentence would be wrong here.
    // See [onSettings].
    /** REVIEW CRITICAL: what deleting the account removes, from anonymise_user (V23). */
    val settingsDeleteAccountBlurb: String,
    /** REVIEW CRITICAL: the irreversible confirmation. */
    val settingsDeleteAccountConfirmBody: String,
    // ---- the two cards. The mockup's own words where it has them.
    /** The diagnosis card's title, in sentence case at card-title size (row 41). */
    val diagnosisHeading: String,
    val whatHappenedHeading: String,
    val whereItWentWrongHeading: String,
    val habitToChangeHeading: String,
    /**
     * The three buttons under each finding (release scope row 37, Nathan's wording), in
     * [com.qualityverifier.text.DetailKind] order. Each opens what Claude wrote, or asks for it.
     */
    val detailWhatWeFound: String,
    val detailCause: String,
    val detailNextTime: String,
    /** Under a button while Claude writes its answer. */
    val detailWriting: String,
    /** Before a finding's tool_line: a tool from the maker's own list that does the job faster. */
    val toolLineLabel: String,
    val oneCheckHeading: String,
    val nothingFound: String,
    val horizonNames: Map<FixHorizon, String>,
    val horizonBlurbs: Map<FixHorizon, String>,
    val toolHeading: String,
    private val minutesFormat: String,
    // ---- the intake. Asked before anything is sent, so an abandoned one costs nothing.
    val intakeLanguageTitle: String,
    val intakePurposeTitle: String,
    val intakeReturningTitle: String,
    /** The first intake step: what the maker calls this piece. Optional. */
    val intakeNameTitle: String,
    val intakeNameHint: String,
    val intakeNameSkip: String,
    val purposeNames: Map<FundiPurpose, String>,
    val detailsPrompts: Map<FundiPurpose, String>,
    val intakeIssueTitle: String,
    /** When a verify-fix has no saved finding to pick from and asks for a description. */
    val intakeNoPriorIssues: String,
    val intakeContinue: String,
    val photoInstructions: Map<FundiPurpose, String>,
    /**
     * What the opening photograph shows, as a caption under it in the viewer. The
     * instruction in [photoInstructions] asked for it; under the photo an instruction
     * would read as asking for another one.
     */
    private val openingPhotoNames: Map<FundiPurpose, String>,
    val skipPhoto: String,
    /**
     * Shown the moment the intake is sent. The first reply builds a plan from scratch
     * and takes long enough that silence reads as a hang.
     */
    val processing: String,
    // ---- what the opening turn says on the maker's behalf, purpose first.
    val saysEvaluate: String,
    val saysReevaluate: String,
    private val saysWorkInProgressFormat: String,
    private val saysDiagnoseFormat: String,
    private val saysCustomerReturnFormat: String,
    private val saysLearnFormat: String,
    private val saysOtherFormat: String,
    private val saysNewIssueFormat: String,
    private val saysVerifyFixFormat: String,
    /**
     * Heads the record of the last assessment on a returning piece. The model has no
     * other memory of it: each assessment is its own conversation.
     */
    val previousRecordIntro: String,
    val previousVerdict: String,
    val previousDiagnosis: String,
    val previousCause: String,
    val previousHabit: String,
    val previousUnchecked: String,
    // ---- the conversation
    val replyHint: String,
    val send: String,
    /** How an app-composed turn is shown in place of its full text. */
    private val sentPhotosFormat: String,
    /** A plan submission that carried answers but no photographs. */
    val sentAnswers: String,
    // ---- the worker form (V30): name, number from contacts or typed, employee or temporary
    val workerAddTitle: String,
    val workerEditTitle: String,
    val workerNameHint: String,
    val workerChooseFromContacts: String,
    /** The phone has no contacts app, or the picker would not open. */
    val workerContactsUnavailable: String,
    val workerPhoneLabel: String,
    val workerPhoneHint: String,
    /** One line under the number saying why it is asked for. */
    val workerPhoneWhy: String,
    val workerKindQuestion: String,
    val employmentNames: Map<Employment, String>,
    val workerSave: String,
    val workerCancel: String,
    val workerArchive: String,
    // ---- the worker's page: their pieces, and what each assessment found
    val workerPageEdit: String,
    val workerPageArchived: String,
    val workerPageNoPieces: String,
    val workerPageNoMatch: String,
    val workerPageWholePiece: String,
    val workerPageAllPieces: String,
    val workerPageFrom: String,
    val workerPageTo: String,
    val workerPageClearFilters: String,
    val workerPageOpen: String,
    val workerPageNoVerdict: String,
    val workerPageNoProblems: String,
    /** The newest diagnosis would not decode: not known, which is not the same as none. */
    val workerPageProblemsUnknown: String,
    val workerPagePurposeUnknown: String,
    // ---- "Studios I work for" (V31; "work at" until v0.6.0), and the server's "Linked" mark on both lists
    val studiosTitle: String,
    val studiosEmpty: String,
    val studioAddTitle: String,
    val studioEditTitle: String,
    val studioNameHint: String,
    val studioPhoneLabel: String,
    /** One line under the number saying why it is asked for. */
    val studioPhoneWhy: String,
    val studioArchive: String,
    /** On a worker or studio card: the two-sided match holds. */
    val linkedTag: String,
    // ---- "Send report" (plan v7 §5): the menu item, the first step, the preview, the wait
    val reportMenuItem: String,
    val reportTitle: String,
    val reportWhoFor: String,
    val reportForCustomer: String,
    /** Under the customer choice: what that report carries and what it leaves out. */
    val reportForCustomerHint: String,
    val reportPreviewTitle: String,
    val reportPreviewHint: String,
    private val reportPhotosFormat: String,
    val reportSend: String,
    val reportBack: String,
    val reportPreparing: String,
    private val reportDownloadingFormat: String,
    private val reportDownloadingOfFormat: String,
    val reportUnavailable: String,
    /** Another report was being made; the same words as the connection message, as §5.5 says. */
    val reportBusy: String,
    /** The server refused the request: not this account's piece, or a report that cannot be made. */
    val reportRefused: String,
    val reportRetry: String,
    /** The share sheet's title. */
    val reportShareWith: String,
    // ---- the worker and Me reports (plan v7 PR 4): parts, issues or fixes, which assessment
    val reportForWorker: String,
    val reportForWorkerHint: String,
    val reportForMe: String,
    val reportForMeHint: String,
    val reportWhichParts: String,
    val reportWhichPartsHint: String,
    val reportNext: String,
    val reportIssuesOrFixes: String,
    val reportIssues: String,
    val reportIssuesHint: String,
    val reportFixes: String,
    val reportFixesHint: String,
    val reportWhichAssessment: String,
    /** The unsent worker edits did not reach the server, so the preview would print old names. */
    val reportWorkersUnsent: String,
    // ---- "My manager" and the linked-worker report (Nathan, 12 November 2026)
    val reportForManager: String,
    val reportForManagerHint: String,
    /** On a linked worker's page: the report of their work across pieces. */
    val workerPageSendReport: String,
    // ---- v0.6.0: which studio a piece is for (rows 1 and 2), today's studio (row 9)
    val intakeStudioTitle: String,
    /** The choice for a piece that is not for any studio on the list. */
    val studioOwnJob: String,
    /** Under the choice, once a studio is picked: who can see the assessments (Nathan, 16 November 2026). */
    private val intakeStudioNoticeFormat: String,
    /** On a piece's card: the studio it is for. */
    private val pieceForFormat: String,
    /** On a piece's card, for a piece made before the question existed. */
    val pieceStudioNotChosen: String,
    val pieceStudioChoose: String,
    val pieceStudioChange: String,
    /** In the change dialog, before a choice that spends the one correction. */
    val pieceStudioChangeOnce: String,
    val pieceStudioCancel: String,
    val todayStudioTitle: String,
    /** "Which studio are you at today?": the fundi's own workshop. */
    val todayStudioOwn: String,
    // ---- v0.6.0: a linked worker's pieces on their page (rows 4, 11), the final assessment (row 27)
    val workerPageTheirPieces: String,
    val workerPageTheirPiecesHint: String,
    val workerPageTheirPiecesNone: String,
    val workerPageTheirPiecesOffline: String,
    val workerPageAssessPiece: String,
    val workerPageByThem: String,
    val workerPageByYou: String,
    val workerPageFinal: String,
    val reportFinalNeeded: String,
    val reportAssessFinished: String,
    /** "Is this piece finished?" at the intake, for an account that may make the final assessment. */
    val intakeFinishedTitle: String,
    val intakeFinishedHint: String,
    val intakeFinishedYes: String,
    val intakeFinishedNo: String,
    /**
     * Below the active studios or workers (Nathan, 17 November 2026): opens and closes the
     * archived ones, kept apart from the list; {n} is how many.
     */
    private val archivedShowFormat: String,
    val archivedHide: String,
    /** "Which pieces?" before a worker's work report (row 22). */
    val reportWhichPieces: String,
    val reportWhichPiecesHint: String,
    // ---- v0.6.0: the bench photo at setup (row 5)
    val benchTitle: String,
    val benchBlurb: String,
    val benchTakePhoto: String,
    val benchSkip: String,
    /** Under the camera: what to photograph. */
    val benchCameraInstruction: String,
    val benchSending: String,
    private val benchIsThereFormat: String,
    private val benchWhoOwnsFormat: String,
    val benchYes: String,
    val benchNo: String,
    val benchSkipRest: String,
    val benchLimit: String,
    val benchUnavailable: String,
    val benchUnreadable: String,
    val benchRetry: String,
    // ---- v0.6.0: a defect on its photo (row 19), and the tester question (row 7)
    /** Under a finding whose photo is on the phone: opens it zoomed on the defect. */
    val seeOnPhoto: String,
    val reviewHelpfulQuestion: String,
    val reviewHelpfulYes: String,
    val reviewHelpfulNo: String,
    /** After the tester has answered. */
    val reviewHelpfulThanks: String,
    // ---- v0.6.0: offline (row 6)
    /** Beside a worker or a piece the server has not been told about yet. */
    val notSentYet: String,
    /** Above the workers and the pieces when the last sync failed; {time} is its time, "14:32". */
    private val lastSyncFailedFormat: String,
) {
    fun lastSyncFailed(time: String) = lastSyncFailedFormat.replace("{time}", time)

    fun benchIsThere(tool: String) = benchIsThereFormat.replace("{tool}", tool)

    fun benchWhoOwns(tool: String) = benchWhoOwnsFormat.replace("{tool}", tool)

    fun intakeStudioNotice(studio: String) = intakeStudioNoticeFormat.replace("{studio}", studio)

    fun pieceFor(studio: String) = pieceForFormat.replace("{studio}", studio)

    fun reportPhotos(n: Int) = reportPhotosFormat.replace("{n}", n.toString())

    fun archivedShow(n: Int) = archivedShowFormat.replace("{n}", n.toString())

    /**
     * The "Who is this for?" choice for a kind. A worker's choice is listed under
     * [ReportKind.PART_ISSUES]; issues or fixes is chosen later. "My manager" arrives with
     * the linked-worker report.
     */
    fun reportChoice(kind: ReportKind): String = when (kind) {
        ReportKind.CUSTOMER -> reportForCustomer
        ReportKind.PART_ISSUES, ReportKind.PART_FIXES -> reportForWorker
        ReportKind.FULL -> reportForMe
        ReportKind.TO_MANAGER -> reportForManager
        else -> kind.id
    }

    fun reportChoiceHint(kind: ReportKind): String = when (kind) {
        ReportKind.CUSTOMER -> reportForCustomerHint
        ReportKind.PART_ISSUES, ReportKind.PART_FIXES -> reportForWorkerHint
        ReportKind.FULL -> reportForMeHint
        ReportKind.TO_MANAGER -> reportForManagerHint
        else -> ""
    }

    /** "Downloading the report, 120 of 480 KB", or without the total when the server gave none. */
    fun reportDownloading(receivedKb: Long, totalKb: Long?): String =
        if (totalKb == null) reportDownloadingFormat.replace("{n}", receivedKb.toString())
        else reportDownloadingOfFormat.replace("{n}", receivedKb.toString()).replace("{total}", totalKb.toString())

    fun nameOf(employment: Employment): String = employmentNames.getValue(employment)

    fun saysWorkInProgress(what: String) = saysWorkInProgressFormat.replace("{what}", what)
    fun saysDiagnose(what: String) = saysDiagnoseFormat.replace("{what}", what)
    fun saysCustomerReturn(what: String) = saysCustomerReturnFormat.replace("{what}", what)
    fun saysLearn(what: String) = saysLearnFormat.replace("{what}", what)
    fun saysOther(what: String) = saysOtherFormat.replace("{what}", what)
    fun saysNewIssue(what: String) = saysNewIssueFormat.replace("{what}", what)
    fun saysVerifyFix(what: String) = saysVerifyFixFormat.replace("{what}", what)
    fun sentPhotos(n: Int) = sentPhotosFormat.replace("{n}", n.toString())
    fun setupLocated(metres: Int) = setupLocatedFormat.replace("{m}", metres.toString())
    fun nameOf(purpose: FundiPurpose): String = purposeNames.getValue(purpose)
    fun openingPhotoName(purpose: FundiPurpose): String = openingPhotoNames.getValue(purpose)

    fun minutes(n: Int): String = minutesFormat.replace("{n}", n.toString())


    fun nameOf(horizon: FixHorizon): String = horizonNames.getValue(horizon)
    fun blurbOf(horizon: FixHorizon): String = horizonBlurbs.getValue(horizon)

    fun worksAt(place: String): String = worksAtFormat.replace("{place}", place)
    fun years(count: Int): String = yearsFormat.replace("{n}", count.toString())
    fun workers(count: Int): String = workersFormat.replace("{n}", count.toString())
    fun makes(what: String): String = makesFormat.replace("{what}", what)
    fun piecesPerMonth(count: Int): String =
        piecesPerMonthFormat.replace("{n}", count.toString())
    fun timber(kind: String): String = timberFormat.replace("{kind}", kind)

    /** "a square (borrowed)". Marked because a borrowed tool may not be there next time. */
    fun borrowed(tool: String): String = borrowedFormat.replace("{tool}", tool)

    fun nameOf(kind: ToolKind): String = toolNames[kind] ?: kind.id.replace('_', ' ')

    /**
     * "Hand saw": [nameOf] with its first letter capitalised, for a tool shown on its own as a
     * heading or list item (Nathan, 17 November 2026: lower case read as informal). Screens
     * only. The opening turn keeps [nameOf], so the assessment prompt's input is unchanged.
     */
    fun displayNameOf(kind: ToolKind): String = nameOf(kind).replaceFirstChar { it.titlecase() }
    fun nameOf(goal: FundiGoal): String = goalNames[goal] ?: goal.id.replace('_', ' ')

    /**
     * The settings screen's wording: [base] with Fundi Bora's sentences where Kagua's
     * would be wrong.
     *
     * Kagua's location switch records "at the start of new evaluations" and its hint is
     * about the shops a buyer assesses in and says nothing else is ever recorded; a maker
     * assesses pieces, and may already have recorded their shop at setup. So the switch
     * reads as the question the maker answered ([assessLocationQuestion]). Kagua's
     * deletion text names a business and says reports on the phone are deleted; a maker has
     * pieces and workers, whose names the server clears, and nothing on the phone is
     * deleted. Everything else (the password fields, sign-out) reads the same in both apps,
     * so it stays Kagua's and stays in one place.
     */
    fun onSettings(base: AuthLabels): AuthLabels = base.copy(
        recordLocationLabel = assessLocationSwitch,
        recordLocationHint = assessLocationHint,
        deleteAccountBlurb = settingsDeleteAccountBlurb,
        deleteAccountConfirmBody = settingsDeleteAccountConfirmBody,
    )

    companion object {
        val ENGLISH = FundiLabels(
            code = "en",
            contextIntro = "About my workshop:",
            contextLanguage = "Please answer me in English.",
            contextToolsHave = "Tools I have:",
            // Said out loud rather than left to the absence of a mention. The prompt has to
            // be able to tell "they told us they have none" from "nobody asked".
            contextToolsNone = "Tools I do not have:",
            contextToolsUnknown = "Tools I do not know:",
            contextGoal = "What I want from this:",
            worksAtFormat = "I work at {place}.",
            yearsFormat = "I have been in the trade {n} years.",
            workersFormat = "There are {n} of us working.",
            makesFormat = "I mostly make {what}.",
            piecesPerMonthFormat = "I finish about {n} pieces a month.",
            timberFormat = "I usually work in {kind}.",
            borrowedFormat = "{tool} (borrowed)",
            toolNames = mapOf(
                ToolKind.HAND_SAW to "hand saw",
                ToolKind.HAMMER_MALLET to "hammer or mallet",
                ToolKind.CHISELS to "chisels",
                ToolKind.PLANE_NO4 to "no. 4 plane",
                ToolKind.CIRCULAR_SAW to "circular saw",
                ToolKind.ROUTER to "router",
                ToolKind.MARKING_GAUGE to "marking gauge",
                ToolKind.CLAMPS to "clamps",
                ToolKind.SQUARE to "square",
                ToolKind.DRILL to "drill",
                ToolKind.SANDER to "sander",
                ToolKind.OTHER to "other tools",
            ),
            setupWorkshopTitle = "About your workshop",
            // Every field optional, and the screen says so: a maker who will not say how
            // many pieces they finish should still reach the coaching.
            setupWorkshopBlurb = "All of this is optional. It helps the advice fit the " +
                "work you actually do.",
            setupWorksAt = "Where you work",
            setupAtShopQuestion = "Are you at your shop right now?",
            setupAtShopYes = "Yes, I'm at my shop",
            setupAtShopNo = "No",
            setupGpsQuestion = "Can we record a GPS point for your shop? It helps us " +
                "understand where shops work and link you to opportunities near you. " +
                // Was "…now, and nothing else." until a point per assessment existed: the
                // question below asks about those separately, and "nothing else" beside
                // it would be false for a maker who says yes to both.
                "This records one point for your shop, now.",
            setupGpsYes = "Yes, record it",
            setupGpsNo = "No thanks",
            setupLocating = "Finding your location",
            setupLocatedFormat = "Recorded, accurate to about {m} m",
            setupLocateFailed = "Could not find your location. Try again near a door " +
                "or window, or carry on without it.",
            setupLocateNoPermission = "Location access is off for Fundi Bora, so nothing " +
                "was recorded.",
            setupLocateRetry = "Try again",
            assessLocationQuestion = "Can we record your phone's location each time you " +
                "assess a piece?",
            assessLocationHint = "We use this for research, to know where each " +
                "assessment was made. Nothing is recorded at any other time, apart from " +
                "your shop's location if you chose to record it. You can change this any " +
                "time in Settings.",
            assessLocationYes = "Yes, record it",
            assessLocationNo = "No thanks",
            assessLocationSwitch = "Record my phone's location each time I assess a piece.",
            setupYears = "Years in the trade",
            setupWorkers = "People working with you",
            setupMakes = "What you mostly make",
            setupPiecesPerMonth = "Pieces a month",
            setupTimber = "Timber you usually use",
            setupToolsTitle = "Your tools",
            // The one screen that is not optional in spirit, and the blurb says why
            // rather than enforcing it — a fix built around a tool they do not own is
            // the failure the coaching prompt calls out by name.
            setupToolsBlurb = "The advice is built around what you have. Tell us what " +
                "you do not have too — that is how we avoid suggesting it.",
            setupGoalsTitle = "What you want from this",
            setupGoalsBlurb = "Pick any that fit.",
            ownershipNames = mapOf(
                ToolOwnership.WORKER to "Mine",
                ToolOwnership.STUDIO to "The studio's",
                ToolOwnership.RENTAL to "Rented",
                ToolOwnership.NONE to "Do not have",
                ToolOwnership.UNKNOWN to "Don't know what this tool is",
            ),
            setupNext = "Next",
            setupBack = "Back",
            setupFinish = "Finish setup",
            setupSkip = "Skip for now",
            setupSaving = "Saving…",
            setupFailed = "Could not save that. Check your connection and try again.",
            settingsDeleteAccountBlurb = "We remove your name, phone number, the locations " +
                "we recorded, and the names you gave your pieces and workers from our " +
                "records straight away, and you will not be able to sign in again.\n\nWe " +
                "keep the assessments themselves, under a random number that is not linked " +
                "to you, because they are what this research studies. We cannot take " +
                "personal details out of photos or messages, so please do not put them " +
                "there. This cannot be undone.",
            settingsDeleteAccountConfirmBody = "Your name, phone number, locations, and the " +
                "names of your pieces and workers are removed now. The assessments stay, " +
                "under a random number. You cannot sign in again and this cannot be undone.",
            diagnosisHeading = "Diagnosis",
            whatHappenedHeading = "WHAT HAPPENED",
            whereItWentWrongHeading = "WHERE IT WENT WRONG",
            // "Habit", never the person. The prompt forbids calling a maker careless,
            // and this heading is where that would otherwise creep back in.
            habitToChangeHeading = "THE HABIT TO CHANGE",
            detailWhatWeFound = "More on what we found",
            detailCause = "More on what's causing this",
            detailNextTime = "How to upgrade next time",
            detailWriting = "Writing…",
            toolLineLabel = "TOOL",
            oneCheckHeading = "ONE THING TO CHECK",
            nothingFound = "Nothing to put right on this one.",
            horizonNames = mapOf(
                FixHorizon.FIX_NOW to "FIX NOW",
                FixHorizon.PREVENT to "PREVENT",
                FixHorizon.DRILL to "DRILL",
            ),
            horizonBlurbs = mapOf(
                FixHorizon.FIX_NOW to "This piece, today.",
                FixHorizon.PREVENT to "From the next piece onwards.",
                FixHorizon.DRILL to "Practice on offcuts.",
            ),
            toolHeading = "A TOOL WORTH BUYING",
            minutesFormat = "{n} min",
            intakeLanguageTitle = "Which language should we use?",
            intakePurposeTitle = "What do you want from this assessment?",
            intakeReturningTitle = "What do you want to check on this piece?",
            intakeNameTitle = "What do you call this piece?",
            intakeNameHint = "Dining table for the hotel",
            intakeNameSkip = "Skip",
            purposeNames = mapOf(
                FundiPurpose.EVALUATE to "Evaluate a finished product",
                FundiPurpose.WORK_IN_PROGRESS to "Check work in progress",
                FundiPurpose.DIAGNOSE to "Diagnose and fix a specific issue",
                FundiPurpose.LEARN to "Learn a skill",
                FundiPurpose.OTHER to "Something else",
                FundiPurpose.REEVALUATE to "Re-evaluate this piece",
                FundiPurpose.VERIFY_FIX to "Check that an issue was fixed",
                FundiPurpose.NEW_ISSUE to "A new issue came up",
                FundiPurpose.CUSTOMER_RETURN to "A customer brought it back",
            ),
            detailsPrompts = mapOf(
                FundiPurpose.WORK_IN_PROGRESS to "What stage is it at, and what do you want checked?",
                FundiPurpose.DIAGNOSE to "What is the problem, and where on the piece?",
                FundiPurpose.CUSTOMER_RETURN to "What did the customer say was wrong?",
                FundiPurpose.LEARN to "Which skill do you want to work on?",
                FundiPurpose.OTHER to "Tell us what you would like help with.",
                FundiPurpose.NEW_ISSUE to "What has come up, and where on the piece?",
                FundiPurpose.VERIFY_FIX to "Which issue did you fix?",
            ),
            intakeIssueTitle = "Which issue did you fix?",
            intakeNoPriorIssues = "No earlier findings were saved for this piece. " +
                "Describe the issue you fixed.",
            intakeContinue = "Continue",
            photoInstructions = mapOf(
                FundiPurpose.EVALUATE to "Take one photo of the whole piece.",
                FundiPurpose.WORK_IN_PROGRESS to "Take one photo of the piece as it is now.",
                FundiPurpose.CUSTOMER_RETURN to "Take one photo of the problem.",
                FundiPurpose.REEVALUATE to "Take one photo of the whole piece.",
                FundiPurpose.DIAGNOSE to "Take one photo of the problem.",
                FundiPurpose.NEW_ISSUE to "Take one photo of the problem.",
                FundiPurpose.VERIFY_FIX to "Take one photo of where the issue was.",
                FundiPurpose.LEARN to "Take a photo if it helps, or skip.",
                FundiPurpose.OTHER to "Take a photo if it helps, or skip.",
            ),
            openingPhotoNames = mapOf(
                FundiPurpose.EVALUATE to "The whole piece",
                FundiPurpose.WORK_IN_PROGRESS to "The piece as it is now",
                FundiPurpose.CUSTOMER_RETURN to "The problem",
                FundiPurpose.REEVALUATE to "The whole piece",
                FundiPurpose.DIAGNOSE to "The problem",
                FundiPurpose.NEW_ISSUE to "The problem",
                FundiPurpose.VERIFY_FIX to "Where the issue was",
                FundiPurpose.LEARN to "Your opening photo",
                FundiPurpose.OTHER to "Your opening photo",
            ),
            skipPhoto = "Skip the photo",
            processing = "Processing your information. This may take a little while " +
                "as it works out a plan.",
            saysEvaluate = "I have finished this piece. Please evaluate it.",
            saysReevaluate = "You have assessed this piece before. Please re-evaluate " +
                "it from scratch.",
            saysWorkInProgressFormat = "This piece is not finished yet. Please check it " +
                "before I go further: {what}",
            saysDiagnoseFormat = "I want to diagnose and fix a specific problem: {what}",
            saysCustomerReturnFormat = "You have assessed this piece before. A customer " +
                "brought it back: {what}",
            saysLearnFormat = "I want to learn a skill: {what}",
            saysOtherFormat = "I would like help with something else: {what}",
            saysNewIssueFormat = "You have assessed this piece before. A new problem " +
                "has come up: {what}",
            saysVerifyFixFormat = "You have assessed this piece before. I fixed this " +
                "issue and want to check it is resolved: {what}",
            previousRecordIntro = "What you recorded the last time you assessed this piece. " +
                "It is for comparison: judge the piece from what you are shown now.",
            previousVerdict = "Verdict",
            previousDiagnosis = "Diagnosis",
            previousCause = "Cause",
            previousHabit = "Habit",
            previousUnchecked = "Could not be checked",
            replyHint = "Type a reply",
            send = "Send",
            sentPhotosFormat = "Sent {n} photos",
            sentAnswers = "Sent your answers",
            goalNames = mapOf(
                FundiGoal.PRICE_PER_PIECE to "a better price per piece",
                FundiGoal.MORE_ORDERS to "more orders",
                FundiGoal.ZERO_COMEBACKS to "no pieces coming back for repair",
            ),
            workerAddTitle = "Add a worker",
            workerEditTitle = "Edit worker",
            workerNameHint = "Name or nickname",
            workerChooseFromContacts = "Choose from contacts",
            workerContactsUnavailable = "Could not open your contacts. Type the number instead.",
            workerPhoneLabel = "Phone number (optional)",
            workerPhoneHint = "The 9 digits after +254, for example 712345678",
            // Plain about what the number is for: nothing happens with it until the worker
            // lists this workshop from their own account (V31).
            workerPhoneWhy = "If this person also uses Fundi Bora, their number lets the app " +
                "link them to your workshop later. Nothing is sent to them.",
            workerKindQuestion = "Works here as",
            employmentNames = mapOf(
                Employment.EMPLOYEE to "Employee",
                Employment.TEMPORARY to "Temporary",
            ),
            workerSave = "Save",
            workerCancel = "Cancel",
            workerArchive = "Archive this worker",
            workerPageEdit = "Edit",
            workerPageArchived = "Archived",
            workerPageNoPieces = "Not named on any piece yet. Name them in \"Who worked on " +
                "this?\" when you assess a piece.",
            workerPageNoMatch = "Nothing matches these filters.",
            workerPageWholePiece = "Whole piece",
            workerPageAllPieces = "All pieces",
            workerPageFrom = "From",
            workerPageTo = "To",
            workerPageClearFilters = "Clear filters",
            workerPageOpen = "Open",
            workerPageNoVerdict = "No verdict",
            workerPageNoProblems = "No problems found",
            workerPageProblemsUnknown = "The problems could not be read. Open the assessment.",
            workerPagePurposeUnknown = "Assessment",
            studiosTitle = "Studios I work for",
            // "Other": a manager's own workshop is the account itself, never an entry here
            // (Nathan, 17 November 2026).
            studiosEmpty = "None yet. Add any other workshops you work for. Your own " +
                "workshop is already included. When a workshop also lists you, the app " +
                "links you to it.",
            studioAddTitle = "Add a studio",
            studioEditTitle = "Edit studio",
            studioNameHint = "Workshop or owner's name",
            studioPhoneLabel = "Owner's phone number",
            // The link needs both sides, and nothing is sent to the other side.
            studioPhoneWhy = "The number the workshop owner uses for Fundi Bora. The app " +
                "links you to the workshop only when they list your number too. Nothing " +
                "is sent to them.",
            studioArchive = "I no longer work here",
            linkedTag = "Linked",
            reportMenuItem = "Send report",
            reportTitle = "Send report",
            reportWhoFor = "Who is this for?",
            reportForCustomer = "The customer",
            reportForCustomerHint = "Every assessment of this piece, with photos: the verdict and " +
                "what was found. No names of workers, and nothing written for you.",
            reportPreviewTitle = "Check the report",
            reportPreviewHint = "This is what the PDF will say.",
            reportPhotosFormat = "{n} photos will be included.",
            reportSend = "Send",
            reportBack = "Back",
            reportPreparing = "Preparing the report",
            reportDownloadingFormat = "Downloading the report, {n} KB",
            reportDownloadingOfFormat = "Downloading the report, {n} of {total} KB",
            reportUnavailable = "Report unavailable. Check your connection and try again.",
            reportBusy = "Report unavailable. Check your connection and try again.",
            reportRefused = "This report cannot be made for this piece. If the piece is new, " +
                "open it once with a connection so the server has it, then try again.",
            reportRetry = "Try again",
            reportShareWith = "Send the report with",
            reportForWorker = "A worker",
            reportForWorkerHint = "What one assessment found on the parts you choose, with what to " +
                "do about it, and the names recorded on each part.",
            reportForMe = "Me",
            reportForMeHint = "Everything one assessment found, grouped by part, with the names " +
                "recorded on each part. For your own records.",
            reportWhichParts = "Which parts did they work on?",
            reportWhichPartsHint = "The parts named on this piece come first.",
            reportNext = "Next",
            reportIssuesOrFixes = "What should the report show?",
            reportIssues = "What was found",
            reportIssuesHint = "The problems one assessment found on those parts.",
            reportFixes = "What changed",
            reportFixesHint = "The problems found on those parts last time and this time. " +
                "Nothing is marked fixed: a problem missing this time may have been put right, " +
                "or may not have been photographed.",
            reportWhichAssessment = "Which assessment?",
            reportWorkersUnsent = "Worker changes not yet sent. Connect and try again.",
            reportForManager = "My manager",
            reportForManagerHint = "The full report on one assessment of this piece, with your " +
                "name on it, for the manager of the studio the piece is for.",
            workerPageSendReport = "Send report",
            intakeStudioTitle = "Which studio is this piece for?",
            studioOwnJob = "My own job",
            intakeStudioNoticeFormat = "The manager of {studio} can see your assessments of this piece.",
            pieceForFormat = "For: {studio}",
            pieceStudioNotChosen = "Studio not chosen",
            pieceStudioChoose = "Choose studio",
            pieceStudioChange = "Change studio",
            pieceStudioChangeOnce = "You can change the studio once.",
            pieceStudioCancel = "Cancel",
            todayStudioTitle = "Which studio are you at today?",
            todayStudioOwn = "My own workshop",
            workerPageTheirPieces = "Their pieces for your studio",
            workerPageTheirPiecesHint = "Pieces they made and labelled for your studio, with their " +
                "own assessments. Tap Assess piece to check one yourself.",
            workerPageTheirPiecesNone = "None yet.",
            workerPageTheirPiecesOffline = "Connect to the internet to see their pieces.",
            workerPageAssessPiece = "Assess piece",
            workerPageByThem = "Their assessment",
            workerPageByYou = "Your assessment",
            workerPageFinal = "Final check",
            reportFinalNeeded = "Before a customer report, assess the finished piece once more. " +
                "Any problem it finds is listed in the report.",
            reportAssessFinished = "Assess the finished piece",
            intakeFinishedTitle = "Is this piece finished?",
            intakeFinishedHint = "If it is, you can send this assessment to the customer in a report.",
            intakeFinishedYes = "Yes, it is finished",
            intakeFinishedNo = "No, I am still making it",
            archivedShowFormat = "Show archived ({n})",
            archivedHide = "Hide archived",
            reportWhichPieces = "Which pieces?",
            reportWhichPiecesHint = "The report covers the pieces you choose.",
            benchTitle = "Photograph your bench",
            benchBlurb = "Take one photo of your bench with your tools on it. The app suggests " +
                "your tools from the photo, and you check each one.",
            benchTakePhoto = "Take a photo",
            benchSkip = "Skip, I will list my tools",
            benchCameraInstruction = "Your whole bench, with your tools on it",
            benchSending = "Looking at your bench",
            benchIsThereFormat = "Is this on your bench: {tool}?",
            benchWhoOwnsFormat = "Who owns the {tool}?",
            benchYes = "Yes",
            benchNo = "No",
            benchSkipRest = "List the rest myself",
            benchLimit = "You have sent five bench photos today. List your tools on the next screen.",
            benchUnavailable = "The photo could not be sent. Check your connection and try again, " +
                "or list your tools yourself.",
            benchUnreadable = "The photo could not be read. Take another, or list your tools yourself.",
            benchRetry = "Try again",
            seeOnPhoto = "See it on the photo",
            reviewHelpfulQuestion = "Was this review helpful?",
            reviewHelpfulYes = "Yes",
            reviewHelpfulNo = "No",
            reviewHelpfulThanks = "Thank you. Your answer is saved.",
            notSentYet = "Not sent yet",
            lastSyncFailedFormat = "The last sync at {time} did not finish. What is marked \"Not sent yet\" " +
                "is kept on this phone and is sent at the next sync.",
        )
    }
}
