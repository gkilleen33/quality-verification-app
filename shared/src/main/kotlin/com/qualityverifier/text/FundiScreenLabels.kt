package com.qualityverifier.text

/**
 * The words of Fundi Bora's v0.7.1 screens (rows 44, 45, 46, 55, 56): the welcome screen,
 * Home, the Settings menu, New piece, a piece's page, "All my pieces", the closing screen,
 * and the worker and studio lists. Taken from the approved mockup `App_FundiBora_v2`
 * (Nathan, 24 November 2026).
 *
 * English only, as the rest of Fundi Bora's chrome is (issue #20): the Kiswahili pass comes
 * later (Nathan, 24 November 2026: "the app needs to work structurally first"). Apart from
 * [FundiLabels] so its long constructor and its Kiswahili copy are not reworked for words
 * that have no Kiswahili yet.
 */
data class FundiScreenLabels(
    // The welcome screen (row 56). Approved "for now"; may change after Nathan tries it.
    val welcomeTitle: String = "Check your furniture before you sell it",
    val welcomeHowItWorks: String = "How it works",
    val welcomeStep1: String = "Take photos of a piece",
    val welcomeStep1Line: String = "The app shows which photos to take.",
    val welcomeStep2: String = "Read what to fix",
    val welcomeStep2Line: String = "What to improve first, and why customers care.",
    val welcomeStep3: String = "Send a report",
    val welcomeStep3Line: String = "To a customer, a worker, or your manager.",
    val welcomeSignIn: String = "Sign in",
    val welcomeInvite: String = "I have an invite code",

    // Home
    val home: String = "Home",
    val assessNewPiece: String = "Assess a new piece",
    val assessFirstPiece: String = "Assess your first piece",
    val myPieces: String = "My pieces",
    val myPiecesEmpty: String = "Each piece you assess appears here, newest first.",
    val newTag: String = "New",
    val yourWorkshop: String = "Your workshop",
    val myWorkers: String = "My workers",
    val studiosIWorkFor: String = "Studios I work for",
    val settings: String = "Settings",
    val offlineTitle: String = "Fundi Bora can't reach the server",
    val tryAgain: String = "Try again",

    // The Settings menu (row 46)
    val menuChangePassword: String = "Change your password",
    val menuLocation: String = "Record my location",
    val menuLocationLine: String = "Each time you assess a piece, for research",
    val menuOn: String = "On",
    val menuOff: String = "Off",
    val menuAgreement: String = "Your agreement",
    val menuAgreementLine: String = "Sending your information to the United States",
    val menuSignOut: String = "Sign out",
    val menuDeleteAccount: String = "Delete my account",
    val menuClose: String = "Close the menu",

    // All my pieces
    val allPieces: String = "All my pieces",
    val inProgress: String = "In progress",
    val finished: String = "Finished",
    val noneHere: String = "None.",

    // A piece's page
    val reassess: String = "Re-assess",
    val assessPiece: String = "Assess piece",
    val sendReport: String = "Send report",
    val assessments: String = "Assessments",
    val yourAssessment: String = "Your assessment",
    val theirAssessment: String = "Their assessment",
    val aboutThisPiece: String = "About this piece",
    val name: String = "Name",
    val studio: String = "Studio",
    val whoWorkedOnThis: String = "Who worked on this?",
    val notNamedYet: String = "Not named yet",
    val edit: String = "Edit",
    val forOwnJob: String = "My own job",
    val notAskedStudio: String = "Not chosen yet",
    val changeStudioOnce: String = "You can change the studio once.",
    val studioChangeUsed: String = "The studio has been changed once already.",
    val save: String = "Save",
    val cancel: String = "Cancel",
    val renameFoot: String = "Saving it blank goes back to the kind of piece.",

    // New piece (row 46)
    val newPieceTitle: String = "New piece",
    val newPieceWhat: String = "What are you working on?",
    val newPieceWho: String = "Who is it for?",
    val newPieceStart: String = "Start: take the photos",

    // My workers, a worker's page, Studios (row 44)
    val addWorker: String = "Add a worker",
    val workersEmpty: String = "Add the people who work with you, so your reports and assessments can name them.",
    val linked: String = "Linked",
    val notLinked: String = "Not linked",
    val archived: String = "Archived",
    val theirPiecesForYourStudio: String = "Their pieces for your studio",
    val piecesYouNamedThemOn: String = "Pieces you named them on",
    val noneYet: String = "None yet.",
    val theirPiecesOffline: String = "Their pieces need a connection. Try again when you're online.",
    val addStudio: String = "Add a studio",
    val ownWorkshopIncluded: String = "Your own workshop is already included.",
    val loading: String = "Loading…",
) {
    /** "For: Example studio, Gikomba" under a piece's name. */
    fun pieceFor(studio: String) = "For: $studio"

    /** "Made by Otieno and labelled for your studio", on a linked worker's piece. */
    fun madeBy(worker: String?) =
        if (worker == null) "Made by a linked worker and labelled for your studio"
        else "Made by $worker and labelled for your studio"

    /** "20 Nov · Your assessment". */
    fun assessmentRow(day: String, theirs: Boolean) = "$day · " + if (theirs) theirAssessment else yourAssessment

    /** "All my pieces (5)". */
    fun allPiecesCount(n: Int) = "$allPieces ($n)"

    /** "Show archived (2)". */
    fun showArchived(n: Int) = "Show archived ($n)"

    /** The red box's line: "You're seeing the pieces saved on this phone, from 20 Nov at 10:14. Check …". */
    fun offlineLine(day: String?, time: String?) =
        if (day == null || time == null) "You're seeing the pieces saved on this phone. Check your internet connection."
        else "You're seeing the pieces saved on this phone, from $day at $time. Check your internet connection."

    /** "The manager of Example studio can see your assessments of this piece." */
    fun studioSees(studio: String) = "The manager of $studio can see your assessments of this piece."

    companion object {
        val ENGLISH = FundiScreenLabels()
    }
}
