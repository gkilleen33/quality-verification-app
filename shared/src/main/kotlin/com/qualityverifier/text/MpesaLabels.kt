package com.qualityverifier.text

import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.MpesaType
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * Fundi Bora's Finances tab, the M-Pesa questions around their agreed text, a transaction's
 * screen, a piece's spending, and the two switches in Settings.
 *
 * English only, as the rest of Fundi Bora is (see [FundiLabels]); no Kiswahili instance
 * until the app's Kiswahili pass. The questions' own words are [MpesaConsentText], which
 * is part of what a fundi agrees to and changes only with a new version.
 */
data class MpesaLabels(
    // ---- the two questions
    val readTitle: String,
    val sendTitle: String,
    val yes: String,
    val notNow: String,
    val permissionRefused: String,
    val offline: String,
    val failed: String,
    // ---- the Finances tab
    val piecesTab: String,
    val financesTab: String,
    val financesTitle: String,
    val offHeading: String,
    val offBody: String,
    val turnOn: String,
    val permissionMissing: String,
    val allowPermission: String,
    val thisMonth: String,
    val allTime: String,
    val moneyIn: String,
    val moneyOut: String,
    val empty: String,
    val notSending: String,
    val noCategory: String,
    // ---- one transaction
    val transactionTitle: String,
    val whatFor: String,
    val whichPiece: String,
    val noPiece: String,
    val details: String,
    val detailsHint: String,
    val save: String,
    val cancel: String,
    val codeLabel: String,
    val feeLabel: String,
    val balanceLabel: String,
    val notFound: String,
    // ---- a piece's card
    val spentOnPiece: String,
    // ---- Settings
    val settingsHeading: String,
    val readSwitch: String,
    val readSwitchHelp: String,
    val sendSwitch: String,
    val sendSwitchHelp: String,
    val sendNeedsRead: String,
) {
    /** "3 payments, KSh 4,500". */
    fun countAndAmount(count: Int, amountKes: Double): String =
        "$count ${if (count == 1) "payment" else "payments"}, ${kes(amountKes)}"

    /** "KSh 1,500" or "KSh 1,500.50": cents only when there are any. */
    fun kes(amountKes: Double): String {
        val pattern = if (amountKes % 1.0 == 0.0) "#,##0" else "#,##0.00"
        return "KSh " + DecimalFormat(pattern, DecimalFormatSymbols(Locale.UK)).format(amountKes)
    }

    fun category(category: MpesaCategory): String = when (category) {
        // Grady's wording (Slack, 11 November 2026).
        MpesaCategory.MATERIAL_PURCHASES -> "Material purchases"
        MpesaCategory.WAGES -> "Wages"
        MpesaCategory.MACHINE_RENTAL -> "Machine rental"
        MpesaCategory.CUSTOMER_PAYMENT -> "Money from a customer"
        MpesaCategory.OTHER -> "Other"
    }

    /** What happened, as the list's first line says it. */
    fun type(type: MpesaType): String = when (type) {
        MpesaType.SEND -> "Sent"
        MpesaType.RECEIVE -> "Received"
        MpesaType.PAYBILL -> "Paid (Paybill)"
        MpesaType.BUY_GOODS -> "Paid (Buy Goods)"
        MpesaType.BUNDLE -> "Bundle bought"
        MpesaType.WITHDRAW -> "Cash withdrawn"
        MpesaType.DEPOSIT -> "Cash deposited"
        MpesaType.AIRTIME -> "Airtime bought"
        MpesaType.FULIZA_DRAW -> "Fuliza used"
        MpesaType.FULIZA_REPAY -> "Fuliza repaid"
        MpesaType.SAVINGS_DEPOSIT -> "Moved to savings"
        MpesaType.SAVINGS_WITHDRAW -> "Moved from savings"
        MpesaType.REVERSAL -> "Reversed"
    }

    companion object {
        val ENGLISH = MpesaLabels(
            readTitle = "Your M-Pesa messages",
            sendTitle = "Sending your M-Pesa payments",
            yes = "Yes",
            notNow = "Not now",
            permissionRefused = "Fundi Bora cannot read your M-Pesa messages without permission to " +
                "read SMS, so reading stays off. You can turn it on later in Settings.",
            offline = "No internet connection. Your answer has not been saved. Try again when you " +
                "are online.",
            failed = "Something went wrong. Please try again.",
            piecesTab = "Pieces",
            financesTab = "Finances",
            financesTitle = "Finances",
            offHeading = "See the money coming into and going out of your business",
            offBody = "Fundi Bora can read your M-Pesa messages and add up your payments for you. " +
                "It reads only M-Pesa messages that arrive after you agree.",
            turnOn = "Turn on",
            permissionMissing = "Fundi Bora no longer has permission to read SMS, so it cannot read " +
                "new M-Pesa messages.",
            allowPermission = "Allow",
            thisMonth = "This month",
            allTime = "All time",
            moneyIn = "Money in",
            moneyOut = "Money out",
            empty = "No M-Pesa payments yet. New ones appear here when their messages arrive.",
            notSending = "Sending is off. Your payments stay on this phone.",
            noCategory = "No category yet",
            transactionTitle = "Payment",
            whatFor = "What was it for?",
            whichPiece = "Which piece was it for?",
            noPiece = "No piece",
            details = "Details",
            detailsHint = "For example, what you bought or who you paid",
            save = "Save",
            cancel = "Cancel",
            codeLabel = "M-Pesa code",
            feeLabel = "Fee",
            balanceLabel = "Balance after",
            notFound = "This payment is no longer on this phone.",
            spentOnPiece = "Spent on this piece",
            settingsHeading = "M-Pesa messages",
            readSwitch = "Read my M-Pesa messages",
            readSwitchHelp = "Only M-Pesa messages that arrive after you agree. Turning this off " +
                "stops reading at once.",
            sendSwitch = "Send my M-Pesa payments to Fundi Bora",
            sendSwitchHelp = "Turning this off stops sending. Your payments stay on this phone.",
            sendNeedsRead = "Turn on reading first.",
        )
    }
}
