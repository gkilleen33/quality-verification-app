package com.qualityverifier.text

/**
 * Wording for a turn that has no reply: the error row after a send fails, and the row
 * offered when an assessment reopens on a turn that never got one.
 *
 * Kagua's wording, moved here from its chat screen in v0.4.0 so that Fundi Bora says the
 * same thing rather than a copy that drifts. English only, as both apps show it today:
 * Kagua's chat screen never translated these, and Fundi Bora is English throughout (see
 * FundiLabels).
 */
data class RetryLabels(
    /** Resends a turn that has just failed. */
    val retry: String,
    /** Closes the error row and leaves the turn as it is. */
    val dismiss: String,
    /** Resends a turn found unanswered, usually after the app was closed mid-request. */
    val sendAgain: String,
    /** Above [sendAgain]: what happened, and that nothing has to be redone. */
    val unanswered: String,
) {
    companion object {
        val ENGLISH = RetryLabels(
            retry = "Retry",
            dismiss = "Dismiss",
            sendAgain = "Send again",
            unanswered = "This didn't get an answer. Your photos and answers are still here — " +
                "send it again when you have signal.",
        )
    }
}
