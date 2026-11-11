package com.qualityverifier.fundi.ui.terms

/** The screens the terms decide between. Navigation maps each to a route. */
enum class TermsStep {
    SIGN_IN,
    TERMS,
    HOME,
    SETUP,

    /** Back to the screen the server refused, where the turn waits for Retry. */
    BACK,
}

/**
 * Where Fundi Bora goes around the terms screen, as plain functions so each rule has a test.
 *
 * The phone keeps the server's last answer for the account (TokenStore.termsRequired):
 * true, false, or null for never asked. Only a stored false skips the terms screen, so an
 * install upgraded from v0.4.0, which has no answer yet, asks the server on its first open
 * and moves on at once if the account already agreed elsewhere.
 */
object TermsRules {

    /** The first screen when the app opens. */
    fun start(signedIn: Boolean, termsRequired: Boolean?): TermsStep = when {
        !signedIn -> TermsStep.SIGN_IN
        termsRequired == false -> TermsStep.HOME
        else -> TermsStep.TERMS
    }

    /** After signing in. A second phone on an account that agreed goes straight home. */
    fun afterSignIn(termsRequired: Boolean?): TermsStep =
        if (termsRequired == false) TermsStep.HOME else TermsStep.TERMS

    /**
     * After the terms are agreed, or found already agreed. A changed-terms screen returns to
     * the screen it covers; a withdrawal returns home; otherwise setup when the server holds
     * no workshop profile, so a new account, or a maker who quit at the terms screen, still
     * reaches setup.
     */
    fun afterTerms(mode: TermsMode, next: AfterTerms): TermsStep = when (mode) {
        TermsMode.CHANGED -> TermsStep.BACK
        TermsMode.WITHDRAWN -> TermsStep.HOME
        TermsMode.NEW, TermsMode.RETURNING -> if (next == AfterTerms.SETUP) TermsStep.SETUP else TermsStep.HOME
    }
}
