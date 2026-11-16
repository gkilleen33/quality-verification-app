package com.qualityverifier.text

/**
 * PesaLog's screens: sign-in, the guided step to notification access, the home screen with
 * the log, one payment, and Settings. The two questions' own words are [TrackerConsentText]
 * and the terms are a published text; neither is here.
 *
 * Built with the app's name ([english]), which PesaLog's build file sets in one place, so a
 * new name needs no change here. English only, as Fundi Bora is; no Kiswahili has been
 * drafted.
 */
data class TrackerLabels(
    val appName: String,
    // ---- sign-in
    val signInBlurb: String,
    val signIn: String,
    val haveInvite: String,
    val inviteBlurb: String,
    val inviteCode: String,
    val yourName: String,
    val proceed: String,
    val haveAccount: String,
    val password: String,
    val passwordHint: String,
    val createAccount: String,
    val changeDetails: String,
    val enterAgain: String,
    val notAFundiAccount: String,
    // ---- the two questions
    val readTitle: String,
    val sendTitle: String,
    val yes: String,
    val notNow: String,
    val offline: String,
    val failed: String,
    // ---- notification access
    val accessTitle: String,
    val accessBody: String,
    val accessSteps: String,
    val restrictedTitle: String,
    val restrictedSteps: String,
    val openAccessSettings: String,
    val openAppInfo: String,
    val accessOn: String,
    val continueLabel: String,
    val later: String,
    val noSettingsPage: String,
    // ---- home
    val statusTitle: String,
    val readingOn: String,
    val readingOff: String,
    val accessGranted: String,
    val accessMissing: String,
    val listenerStopped: String,
    val lastMessage: String,
    val noMessageYet: String,
    val sendingOn: String,
    val sendingOff: String,
    val turnOnAccess: String,
    val turnOnReading: String,
    val thisMonth: String,
    val allTime: String,
    val moneyIn: String,
    val moneyOut: String,
    val empty: String,
    val noCategory: String,
    val settings: String,
    // ---- one payment
    val transactionTitle: String,
    val whatFor: String,
    val details: String,
    val detailsHint: String,
    val save: String,
    val cancel: String,
    val codeLabel: String,
    val networkLabel: String,
    val feeLabel: String,
    val balanceLabel: String,
    val notFound: String,
    // ---- Settings
    val settingsTitle: String,
    val readSwitch: String,
    val readSwitchHelp: String,
    val sendSwitch: String,
    val sendSwitchHelp: String,
    val sendNeedsRead: String,
    val accessRow: String,
    val deleteHeading: String,
    val deleteBlurb: String,
    val deleteButton: String,
    val deleteConfirmTitle: String,
    val deleteConfirmBody: String,
    val deleteConfirm: String,
    val deleteKeep: String,
    val deleted: String,
    val deleteOffline: String,
    val deleteFailed: String,
    val signOut: String,
) {
    /** "Deleted 12 payments and 3 daily counts from the server. Sending is now off." */
    fun deletedSummary(transactions: Int, unreadDays: Int, sendingWithdrawn: Boolean): String =
        "Deleted ${plural(transactions, "payment")} and ${plural(unreadDays, "daily count")} from the server." +
            if (sendingWithdrawn) " Sending is now off." else ""

    private fun plural(count: Int, noun: String) = "$count ${if (count == 1) noun else noun + "s"}"

    companion object {
        fun english(appName: String) = TrackerLabels(
            appName = appName,
            signInBlurb = "Sign in with your Fundi Bora account, or the account the research team gave you.",
            signIn = "Sign in",
            haveInvite = "I have an invite code",
            inviteBlurb = "You need an invite code from the research team to create an account.",
            inviteCode = "Invite code",
            yourName = "Your name",
            proceed = "Proceed",
            haveAccount = "I already have an account",
            password = "Password",
            passwordHint = "At least 8 characters.",
            createAccount = "Create account",
            changeDetails = "Change details",
            enterAgain = "Please enter your details again.",
            notAFundiAccount = "This account is for Kagua. $appName needs a Fundi Bora account, or one " +
                "the research team gave you for $appName.",
            readTitle = "Reading your mobile money notifications",
            sendTitle = "Sending your payments to the research team",
            yes = "Yes",
            notNow = "Not now",
            offline = "No internet connection. Your answer has not been saved. Try again when you are online.",
            failed = "Something went wrong. Please try again.",
            accessTitle = "Turn on notification access",
            accessBody = "$appName reads your mobile money messages from the notifications your phone " +
                "shows. Android calls this permission notification access. It lets an app see every " +
                "notification, and $appName ignores all but mobile money messages from M-Pesa and " +
                "Airtel Money.",
            accessSteps = "1. Tap Open settings.\n2. Find $appName and turn it on.\n3. Tap Allow, then " +
                "come back to $appName.",
            restrictedTitle = "If Android says the setting is restricted",
            restrictedSteps = "Android blocks this setting for apps installed from outside an app store " +
                "until you allow it:\n1. Tap Open App info.\n2. Tap the three dots at the top right.\n" +
                "3. Tap Allow restricted settings, and confirm.\n4. Come back and tap Open settings again.",
            openAccessSettings = "Open settings",
            openAppInfo = "Open App info",
            accessOn = "Notification access is on.",
            continueLabel = "Continue",
            later = "Later",
            noSettingsPage = "This phone did not open the settings page. Open Settings, search for " +
                "\"notification access\", and turn on $appName.",
            statusTitle = "Status",
            readingOn = "Reading mobile money notifications: on",
            readingOff = "Reading mobile money notifications: off",
            accessGranted = "Notification access: on",
            accessMissing = "Notification access: off. $appName cannot read new messages.",
            listenerStopped = "Android has stopped $appName's notification reader. Turn notification " +
                "access off and on again to restart it.",
            lastMessage = "Last mobile money message read",
            noMessageYet = "No mobile money message yet",
            sendingOn = "Sending to the research team: on",
            sendingOff = "Sending to the research team: off. Your payments stay on this phone.",
            turnOnAccess = "Turn on notification access",
            turnOnReading = "Turn on reading",
            thisMonth = "This month",
            allTime = "All time",
            moneyIn = "Money in",
            moneyOut = "Money out",
            empty = "No payments yet. New ones appear here when their messages arrive.",
            noCategory = "No category yet",
            settings = "Settings",
            transactionTitle = "Payment",
            whatFor = "What was it for?",
            details = "Details",
            detailsHint = "For example, what you bought or who you paid",
            save = "Save",
            cancel = "Cancel",
            codeLabel = "Transaction code",
            networkLabel = "Network",
            feeLabel = "Fee",
            balanceLabel = "Balance after",
            notFound = "This payment is no longer on this phone.",
            settingsTitle = "Settings",
            // Terms section 8 names these two switches and the delete button.
            readSwitch = "Read mobile money notifications",
            readSwitchHelp = "Only M-Pesa and Airtel Money messages that arrive after you agree. Turning " +
                "this off stops reading at once.",
            sendSwitch = "Send to the research team",
            sendSwitchHelp = "Turning this off stops sending. Your payments stay on this phone.",
            sendNeedsRead = "Turn on reading first.",
            accessRow = "Notification access",
            deleteHeading = "Your records on the server",
            deleteBlurb = "You can delete every payment record $appName sent from your account. DIL " +
                "keeps only the date of the deletion and the number of records deleted.",
            deleteButton = "Delete my records from the server",
            deleteConfirmTitle = "Delete your records from the server?",
            deleteConfirmBody = "Every payment record and daily count $appName sent from your account " +
                "is deleted from the server. This also turns off sending. The payments stay on this " +
                "phone.",
            deleteConfirm = "Delete",
            deleteKeep = "Keep them",
            deleted = "Deleted.",
            deleteOffline = "No internet connection. Nothing was deleted. Try again when you are online.",
            deleteFailed = "Your records could not be deleted. Please try again.",
            signOut = "Sign out",
        )
    }
}
