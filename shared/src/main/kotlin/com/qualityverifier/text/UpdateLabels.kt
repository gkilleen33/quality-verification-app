package com.qualityverifier.text

/**
 * Wording for the update banner and the update-required screen.
 *
 * Picked by the phone's language, like [AuthLabels], because the banner appears before and
 * outside any assessment. Fundi Bora passes [ENGLISH] for now, matching the rest of that
 * app (see FundiLabels).
 *
 * TRANSLATION STATUS: the Swahili below is unreviewed, like the rest. Every string other
 * than "A new version is available" and "Download" is a draft awaiting Nathan's approval.
 */
data class UpdateLabels(
    val code: String,
    /** The banner's line. Decided wording. */
    val newVersionAvailable: String,
    /** The button on both the banner and the blocking screen. Decided wording. */
    val download: String,
    /** Closes the banner until the app is next started. */
    val dismiss: String,
    /** Title of the screen shown below the minimum version. */
    val updateRequiredTitle: String,
    /** What the blocking screen asks the person to do. */
    val updateRequiredBody: String,
) {
    companion object {
        val ENGLISH = UpdateLabels(
            code = "en",
            newVersionAvailable = "A new version is available",
            download = "Download",
            dismiss = "Not now",
            updateRequiredTitle = "Please update the app",
            updateRequiredBody = "This version no longer works with our server. Download " +
                "the new version and install it to carry on. Your assessments stay on this " +
                "phone.",
        )

        val SWAHILI = UpdateLabels(
            code = "sw",
            newVersionAvailable = "Toleo jipya linapatikana",
            download = "Pakua",
            dismiss = "Si sasa",
            updateRequiredTitle = "Tafadhali sasisha programu",
            updateRequiredBody = "Toleo hili halifanyi kazi tena na seva yetu. Pakua toleo " +
                "jipya na ulisakinishe ili uendelee. Ukaguzi wako unabaki kwenye simu hii.",
        )

        /** By the phone's language; English is the fallback, as in [AuthLabels.forDevice]. */
        fun forDevice(language: String?): UpdateLabels {
            val normalised = language?.trim()?.lowercase().orEmpty()
            return if (normalised.startsWith("sw")) SWAHILI else ENGLISH
        }
    }
}
