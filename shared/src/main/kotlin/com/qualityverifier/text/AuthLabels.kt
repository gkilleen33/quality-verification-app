package com.qualityverifier.text

/**
 * Wording for the account screens: sign in, register, settings, and the delete dialog.
 *
 * Separate from [ReportLabels] because it turns on a different thing. A report is written
 * in the language of its assessment, which the customer chose. These screens come *before*
 * any assessment exists, so there is no choice to honour yet and the phone's own language
 * is the only signal available.
 *
 * TRANSLATION STATUS: the Swahili below is unreviewed, like the rest.
 *
 * The strings marked **REVIEW CRITICAL** need a native speaker before any pilot, more than
 * the others do: they state what we do with somebody's photographs and how long we keep
 * them. Earlier these were deliberately left in English on the grounds that a
 * mistranslated retention notice is worse than an English one. That was wrong — an English
 * paragraph in an otherwise Swahili form is not read at all, and an unread notice informs
 * nobody, while an imperfect one conveys most of it. So they are translated and flagged
 * rather than omitted.
 */
data class AuthLabels(
    val code: String,
    // Sign in
    val tagline: String,
    val phoneLabel: String,
    val phoneHint: String,
    val passwordLabel: String,
    val signIn: String,
    val goToRegister: String,
    // Register
    val createAccount: String,
    /** REVIEW CRITICAL: says what the name and business are used for. */
    val createAccountBlurb: String,
    val inviteLabel: String,
    val nameLabel: String,
    val choosePasswordLabel: String,
    val passwordHint: String,
    val accountTypeQuestion: String,
    val forMyself: String,
    val forBusiness: String,
    val businessNameLabel: String,
    /** REVIEW CRITICAL: states that location is optional and not tracked afterwards. */
    val locationBlurb: String,
    val saveLocation: String,
    val goToSignIn: String,
    // Settings
    val accountSettings: String,
    val changePassword: String,
    val changePasswordBlurb: String,
    val currentPassword: String,
    val newPassword: String,
    val newPasswordAgain: String,
    val passwordChanged: String,
    val deleteAccount: String,
    /** REVIEW CRITICAL: states the 30-day account retention. */
    val deleteAccountBlurb: String,
    val deleteAccountConfirmTitle: String,
    /** REVIEW CRITICAL: the irreversible confirmation. */
    val deleteAccountConfirmBody: String,
    val keepAccount: String,
    // Deleting one assessment
    val deleteReportTitle: String,
    /** REVIEW CRITICAL: states the 7-day assessment retention. */
    val deleteReportBody: String,
    val delete: String,
    val cancel: String,
    val signOut: String,
    val signOutConfirmBody: String,
    val staySignedIn: String,
    /** REVIEW CRITICAL: says a person may read an assessment, including its photos. */
    val humanReviewNotice: String,
    private val savedLocationFormat: String,
) {
    /** "Location saved (within 8m)" — the accuracy is part of the label, not hidden. */
    fun savedLocation(metres: Int): String =
        savedLocationFormat.replace("{m}", metres.toString())

    companion object {
        val ENGLISH = AuthLabels(
            code = "en",
            tagline = "Jua kabla ya kununua — know before you buy.",
            phoneLabel = "Phone number",
            phoneHint = "Starting with your country code, e.g. +256700123456",
            passwordLabel = "Password",
            signIn = "Sign in",
            goToRegister = "I have an invite code — create an account",
            createAccount = "Create your account",
            createAccountBlurb = "Your name is how we address you in reports you share. " +
                "If you tell us your business, we can group your assessments together.",
            inviteLabel = "Invite code",
            nameLabel = "Your name",
            choosePasswordLabel = "Choose a password",
            passwordHint = "At least 8 characters. Length matters more than symbols.",
            accountTypeQuestion = "Are you buying for yourself or for a business?",
            forMyself = "For myself",
            forBusiness = "For a business",
            businessNameLabel = "Business name",
            locationBlurb = "If you are at the business right now, you can save its " +
                "location. This is optional. We use it only to place your business on a " +
                "map of workshops; we do not track where you are afterwards.",
            saveLocation = "Save this location — only if you are here now",
            goToSignIn = "I already have an account — sign in",
            accountSettings = "Account settings",
            changePassword = "Change your password",
            changePasswordBlurb = "Signing in on your other phones will stop working " +
                "until you sign in again with the new password.",
            currentPassword = "Current password",
            newPassword = "New password",
            newPasswordAgain = "New password again",
            passwordChanged = "Password changed.",
            deleteAccount = "Delete my account",
            deleteAccountBlurb = "Your account and your assessments are deleted from our " +
                "server after 30 days. Reports already on this phone are removed straight " +
                "away. This cannot be undone.",
            deleteAccountConfirmTitle = "Delete your account?",
            deleteAccountConfirmBody = "You will be signed out and cannot sign in again. " +
                "Your assessments are deleted from our server after 30 days. This cannot " +
                "be undone.",
            keepAccount = "Keep my account",
            deleteReportTitle = "Delete this report?",
            deleteReportBody = "It will be removed from your phone straight away. We keep " +
                "a copy on our server for 7 days to check the quality of our assessments, " +
                "then it is deleted for good.",
            delete = "Delete",
            cancel = "Cancel",
            signOut = "Sign out",
            signOutConfirmBody = "You will need your phone number and password to sign in " +
                "again. Assessments already on this phone stay on it.",
            staySignedIn = "Stay signed in",
            humanReviewNotice = "Researchers working on Kagua can open an assessment, " +
                "including its photos, to check how accurate our advice was.",
            savedLocationFormat = "Location saved (within {m}m)",
        )

        val SWAHILI = AuthLabels(
            code = "sw",
            tagline = "Jua kabla ya kununua.",
            phoneLabel = "Namba ya simu",
            phoneHint = "Anza na namba ya nchi, mfano +256700123456",
            passwordLabel = "Neno la siri",
            signIn = "Ingia",
            goToRegister = "Nina kodi ya mwaliko — fungua akaunti",
            createAccount = "Fungua akaunti yako",
            createAccountBlurb = "Jina lako ni tunalotumia kukuita katika ripoti " +
                "unazoshiriki. Ukituambia biashara yako, tunaweza kuweka ukaguzi wako pamoja.",
            inviteLabel = "Kodi ya mwaliko",
            nameLabel = "Jina lako",
            choosePasswordLabel = "Chagua neno la siri",
            passwordHint = "Herufi 8 au zaidi. Urefu ni muhimu kuliko alama.",
            accountTypeQuestion = "Unanunua kwa nafsi yako au kwa biashara?",
            forMyself = "Kwa nafsi yangu",
            forBusiness = "Kwa biashara",
            businessNameLabel = "Jina la biashara",
            locationBlurb = "Ikiwa upo kwenye biashara sasa hivi, unaweza kuhifadhi " +
                "mahali pake. Hii ni hiari. Tunatumia tu kuweka biashara yako kwenye " +
                "ramani ya karakana; hatufuatilii ulipo baada ya hapo.",
            saveLocation = "Hifadhi mahali hapa — tu ikiwa upo hapa sasa",
            goToSignIn = "Nina akaunti tayari — ingia",
            accountSettings = "Mipangilio ya akaunti",
            changePassword = "Badilisha neno lako la siri",
            changePasswordBlurb = "Kuingia kwenye simu zako nyingine kutasimama hadi " +
                "uingie tena kwa neno jipya la siri.",
            currentPassword = "Neno la siri la sasa",
            newPassword = "Neno jipya la siri",
            newPasswordAgain = "Rudia neno jipya la siri",
            passwordChanged = "Neno la siri limebadilishwa.",
            deleteAccount = "Futa akaunti yangu",
            deleteAccountBlurb = "Akaunti yako na ukaguzi wako vinafutwa kwenye seva " +
                "yetu baada ya siku 30. Ripoti zilizo kwenye simu hii zinafutwa mara moja. " +
                "Hii haiwezi kurudishwa.",
            deleteAccountConfirmTitle = "Futa akaunti yako?",
            deleteAccountConfirmBody = "Utatolewa nje na hutaweza kuingia tena. Ukaguzi " +
                "wako unafutwa kwenye seva yetu baada ya siku 30. Hii haiwezi kurudishwa.",
            keepAccount = "Baki na akaunti yangu",
            deleteReportTitle = "Futa ripoti hii?",
            deleteReportBody = "Itatolewa kwenye simu yako mara moja. Tunabaki na nakala " +
                "kwenye seva yetu kwa siku 7 kuangalia ubora wa ukaguzi wetu, kisha " +
                "inafutwa kabisa.",
            delete = "Futa",
            cancel = "Acha",
            signOut = "Toka",
            signOutConfirmBody = "Utahitaji namba yako ya simu na neno la siri kuingia " +
                "tena. Ukaguzi ulio kwenye simu hii unabaki.",
            staySignedIn = "Baki umeingia",
            humanReviewNotice = "Watafiti wanaofanya kazi na Kagua wanaweza kufungua " +
                "ukaguzi, pamoja na picha zake, kuangalia usahihi wa ushauri wetu.",
            savedLocationFormat = "Mahali yamehifadhiwa (ndani ya mita {m})",
        )

        /**
         * Picks by the phone's language, which is the only signal available before any
         * assessment exists. English is the fallback rather than the default.
         */
        fun forDevice(language: String?): AuthLabels {
            val normalised = language?.trim()?.lowercase().orEmpty()
            return if (normalised.startsWith("sw")) SWAHILI else ENGLISH
        }
    }
}
