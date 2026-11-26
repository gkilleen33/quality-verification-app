package com.qualityverifier.text

/**
 * The words of Kagua's v2 screens (v0.7.1, release scope rows 45, 46 and 57; the Kagua v2
 * mockup Nathan approved on 24 November 2026, `App_Kagua_v2.dc.html`): the welcome screen,
 * Home, the tabs, My reports, the report page, a problem's page and the end of the problems,
 * and the Settings menu.
 *
 * English as the mockup writes it. Kiswahili where the screen already followed the language
 * (the report and its problems follow the assessment's; the rest the phone's), written for
 * v0.7.1 and not yet reviewed by a Kiswahili speaker.
 */
data class KaguaScreenLabels(
    val code: String,
    // ---- the welcome screen, before signing in
    val welcomeTitle: String,
    val howItWorks: String,
    val stepChoose: String,
    val stepChooseLine: String,
    val stepPhotos: String,
    val stepPhotosLine: String,
    val stepRead: String,
    val stepReadLine: String,
    val signIn: String,
    val createAccount: String,
    // ---- Home and the tabs
    val homeQuestion: String,
    val tabHome: String,
    val tabReports: String,
    val settings: String,
    val offlineTitle: String,
    val offlineLine: String,
    val tryAgain: String,
    // ---- My reports
    val reportsTitle: String,
    val reportsLine: String,
    val reportsEmpty: String,
    val checkFirstPiece: String,
    val notFinished: String,
    val notFinishedLine: String,
    // ---- the report
    /** The back button where the screen behind has no name of its own. */
    val back: String,
    val whatItMeansForYou: String,
    val whatIFound: String,
    val whatICouldNotCheck: String,
    val whatNext: String,
    private val checkAnotherFormat: String,
    val onlyPriceAsked: String,
    private val compareFormat: String,
    val thisReport: String,
    val shareReport: String,
    val deleteReport: String,
    // ---- a problem's page and the end of the problems
    val wholeReport: String,
    private val problemOfFormat: String,
    val whatISee: String,
    val whatToAskFor: String,
    val askAboutProblem: String,
    val askAboutThis: String,
    val send: String,
    val previousProblem: String,
    val nextProblem: String,
    val done: String,
    private val thatsAllFormat: String,
    val thatsTheOne: String,
    val askSellerToFix: String,
    val askSellerToFixOne: String,
    val backToWholeReport: String,
    // ---- the Settings menu
    val changePassword: String,
    val recordGps: String,
    val recordGpsLine: String,
    val on: String,
    val off: String,
    val signOut: String,
    val deleteAccount: String,
    val close: String,
    private val versionFormat: String,
) {
    /** "Check another table": [item] as [ReportLabels.itemName] gives it, in lower case. */
    fun checkAnother(item: String) = checkAnotherFormat.replace("{item}", item)

    fun compare(item: String) = compareFormat.replace("{item}", item)

    /** "2 of 3", a problem's place among the report's problems. */
    fun problemOf(n: Int, total: Int) = problemOfFormat.replace("{n}", n.toString()).replace("{total}", total.toString())

    fun thatsAll(n: Int) = if (n == 1) thatsTheOne else thatsAllFormat.replace("{n}", n.toString())

    fun askSeller(n: Int) = if (n == 1) askSellerToFixOne else askSellerToFix

    fun version(name: String) = versionFormat.replace("{version}", name)

    companion object {
        val ENGLISH = KaguaScreenLabels(
            code = "en",
            welcomeTitle = "Check furniture before you buy it",
            howItWorks = "How it works",
            stepChoose = "Choose what you're looking at",
            stepChooseLine = "A table, a chair, a bed, a sofa, and more.",
            stepPhotos = "Take photos as the app shows you",
            stepPhotosLine = "About eight photos, in the shop.",
            stepRead = "Read what to ask the seller for",
            stepReadLine = "Before you pay.",
            signIn = "Sign in",
            createAccount = "Create an account",
            homeQuestion = "Karibu. What are you looking at today?",
            tabHome = "Home",
            tabReports = "My reports",
            settings = "Settings",
            offlineTitle = "Kagua can't reach the server",
            offlineLine = "You can't check a new piece until you're online. Your reports on this phone still open.",
            tryAgain = "Try again",
            reportsTitle = "My reports",
            reportsLine = "Your furniture, newest first.",
            reportsEmpty = "Each piece you check appears here, newest first.",
            checkFirstPiece = "Check your first piece",
            notFinished = "NOT FINISHED",
            notFinishedLine = "You stopped before the photos were done. Tap to carry on.",
            back = "Back",
            whatItMeansForYou = "What it means for you",
            whatIFound = "What I found",
            whatICouldNotCheck = "What I could not check",
            whatNext = "What next?",
            checkAnotherFormat = "Check another {item}",
            onlyPriceAsked = "Only the price is asked again.",
            compareFormat = "Compare with the last {item}",
            thisReport = "This report",
            shareReport = "Share this report",
            deleteReport = "Delete this report",
            wholeReport = "The whole report",
            problemOfFormat = "{n} of {total}",
            whatISee = "What I see",
            whatToAskFor = "What to ask for",
            askAboutProblem = "Ask about this problem",
            askAboutThis = "Ask about this",
            send = "Send",
            previousProblem = "Previous problem",
            nextProblem = "Next problem",
            done = "Done",
            thatsAllFormat = "That's all {n} problems",
            thatsTheOne = "That's the one problem",
            askSellerToFix = "Ask the seller to fix each one before you pay.",
            askSellerToFixOne = "Ask the seller to fix it before you pay.",
            backToWholeReport = "Back to the whole report",
            changePassword = "Change your password",
            recordGps = "Record a GPS point",
            recordGpsLine = "At the start of each assessment, for research",
            on = "On",
            off = "Off",
            signOut = "Sign out",
            deleteAccount = "Delete my account",
            close = "Close the menu",
            versionFormat = "Version {version}",
        )

        /** Written for v0.7.1; not yet reviewed by a Kiswahili speaker. */
        val SWAHILI = KaguaScreenLabels(
            code = "sw",
            welcomeTitle = "Kagua samani kabla ya kununua",
            howItWorks = "Jinsi inavyofanya kazi",
            stepChoose = "Chagua unachokiangalia",
            stepChooseLine = "Meza, kiti, kitanda, kochi, na zaidi.",
            stepPhotos = "Piga picha kama programu inavyokuonyesha",
            stepPhotosLine = "Picha kama nane, dukani.",
            stepRead = "Soma cha kumwomba muuzaji",
            stepReadLine = "Kabla ya kulipa.",
            signIn = "Ingia",
            createAccount = "Fungua akaunti",
            homeQuestion = "Karibu. Unaangalia nini leo?",
            tabHome = "Nyumbani",
            tabReports = "Ripoti zangu",
            settings = "Mipangilio",
            offlineTitle = "Kagua haiwezi kufikia seva",
            offlineLine = "Huwezi kukagua kipande kipya hadi uwe mtandaoni. Ripoti zilizo kwenye simu hii bado zinafunguka.",
            tryAgain = "Jaribu tena",
            reportsTitle = "Ripoti zangu",
            reportsLine = "Samani zako, za karibuni kwanza.",
            reportsEmpty = "Kila kipande unachokagua kinaonekana hapa, cha karibuni kwanza.",
            checkFirstPiece = "Kagua kipande chako cha kwanza",
            notFinished = "HAIJAKAMILIKA",
            notFinishedLine = "Ulisimama kabla picha hazijakamilika. Gusa kuendelea.",
            back = "Rudi",
            whatItMeansForYou = "Maana yake kwako",
            whatIFound = "Nilichokipata",
            whatICouldNotCheck = "Nisichoweza kukagua",
            whatNext = "Sasa nini?",
            checkAnotherFormat = "Kagua {item} nyingine",
            onlyPriceAsked = "Bei pekee ndiyo itaulizwa tena.",
            compareFormat = "Linganisha na {item} iliyopita",
            thisReport = "Ripoti hii",
            shareReport = "Shiriki ripoti hii",
            deleteReport = "Futa ripoti hii",
            wholeReport = "Ripoti nzima",
            problemOfFormat = "{n} kati ya {total}",
            whatISee = "Ninachokiona",
            whatToAskFor = "Cha kuomba",
            askAboutProblem = "Uliza kuhusu tatizo hili",
            askAboutThis = "Uliza kuhusu hili",
            send = "Tuma",
            previousProblem = "Tatizo lililopita",
            nextProblem = "Tatizo linalofuata",
            done = "Nimemaliza",
            thatsAllFormat = "Hayo ndiyo matatizo yote {n}",
            thatsTheOne = "Hilo ndilo tatizo pekee",
            askSellerToFix = "Mwombe muuzaji arekebishe kila moja kabla hujalipa.",
            askSellerToFixOne = "Mwombe muuzaji alirekebishe kabla hujalipa.",
            backToWholeReport = "Rudi kwenye ripoti nzima",
            changePassword = "Badilisha nenosiri lako",
            recordGps = "Rekodi eneo la GPS",
            recordGpsLine = "Mwanzoni mwa kila ukaguzi, kwa utafiti",
            on = "Imewashwa",
            off = "Imezimwa",
            signOut = "Toka",
            deleteAccount = "Futa akaunti yangu",
            close = "Funga menyu",
            versionFormat = "Toleo {version}",
        )

        fun forLanguage(code: String?): KaguaScreenLabels =
            if (code?.trim()?.lowercase()?.startsWith("sw") == true) SWAHILI else ENGLISH
    }
}
