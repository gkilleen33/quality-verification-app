package com.qualityverifier.text

/**
 * Fundi Bora's terms screen and the withdrawal control in Settings.
 *
 * Its own class rather than more fields on [FundiLabels]: the terms are a legal text with
 * its own approval, and its two checkbox labels are part of what a maker agrees to, so they
 * are kept where a reviewer can read them together.
 *
 * English only, as the rest of Fundi Bora is. A Kiswahili text does not need a Kiswahili
 * instance of this: its file carries its own title, labels and hint (see [TermsDocument]),
 * and [forDocument] uses them over these.
 */
data class TermsLabels(
    val title: String,
    /** The first box: the terms themselves. Unticked by default. */
    val termsLabel: String,
    /** The second box: the transfer of data to the United States, section 14. Unticked by default. */
    val transferLabel: String,
    /** Above the boxes until the end of the text is reached. Read aloud by TalkBack. */
    val scrollHint: String,
    /** Replaces [scrollHint] once the end is reached, and is announced. */
    val boxesEnabled: String,
    val continueLabel: String,
    val signOut: String,
    val accountSettings: String,
    // ---- headings, one per way of arriving at the screen
    val headingNew: String,
    val headingReturning: String,
    val headingChanged: String,
    val headingWithdrawn: String,
    // ---- loading and sending
    val loading: String,
    val loadFailed: String,
    val retry: String,
    val offline: String,
    val sendFailed: String,
    val textChanged: String,
    // ---- withdrawal in Settings
    val withdrawHeading: String,
    val withdrawBlurb: String,
    val withdrawButton: String,
    val withdrawConfirmTitle: String,
    val withdrawConfirmBody: String,
    val withdrawConfirm: String,
    val withdrawKeep: String,
    val withdrawOffline: String,
    val withdrawFailed: String,
) {
    /** These labels, with any a terms file of another language brings with it. */
    fun forDocument(document: TermsDocument): TermsLabels = copy(
        title = document.title ?: title,
        termsLabel = document.termsLabel ?: termsLabel,
        transferLabel = document.transferLabel ?: transferLabel,
        scrollHint = document.hint ?: scrollHint,
    )

    companion object {
        val ENGLISH = TermsLabels(
            title = "Terms and conditions",
            // Nathan's wording, 11 November 2026 (fundi_terms_of_use_v4.md). Part of what is
            // agreed to: change it only with a new terms version.
            termsLabel = "I agree to the terms and conditions.",
            transferLabel = "I agree that my information, including photos of my furniture and my " +
                "workshop location, may be sent to the United States as described in section 14.",
            scrollHint = "Scroll to the end of the terms to agree.",
            boxesEnabled = "You can now tick both boxes.",
            continueLabel = "Continue",
            signOut = "Sign out",
            accountSettings = "Account settings",
            headingNew = "Before you start, please read these terms.",
            headingReturning = "Please read and agree to these terms to continue using Fundi Bora.",
            headingChanged = "There are new terms and conditions. Please read and agree to continue.",
            headingWithdrawn = "You withdrew your agreement to send your information to the United " +
                "States. Fundi Bora cannot work without it. To use Fundi Bora again, read the terms " +
                "and tick both boxes.",
            loading = "Loading the terms…",
            loadFailed = "Connect to the internet to load the terms.",
            retry = "Retry",
            offline = "No internet connection. Your ticks are kept; tap Continue when you are back online.",
            sendFailed = "Something went wrong. Please try again.",
            textChanged = "The terms have just changed. Please read them again.",
            withdrawHeading = "Sending your information to the United States",
            withdrawBlurb = "You agreed that Fundi Bora may send your information, including photos " +
                "of your furniture and your workshop location, to the United States (section 14 of " +
                "the terms). You can withdraw this agreement at any time. Fundi Bora cannot work " +
                "without it.",
            withdrawButton = "Withdraw my agreement",
            withdrawConfirmTitle = "Withdraw your agreement?",
            withdrawConfirmBody = "After you withdraw, you cannot run assessments or change your " +
                "workshop details until you agree again. Your account and past assessments stay on " +
                "the server. To remove them, delete your account.",
            withdrawConfirm = "Withdraw",
            withdrawKeep = "Keep my agreement",
            withdrawOffline = "No internet connection. Your agreement has not been withdrawn yet. " +
                "Try again when you are online.",
            withdrawFailed = "Your agreement could not be withdrawn. Please try again.",
        )
    }
}
