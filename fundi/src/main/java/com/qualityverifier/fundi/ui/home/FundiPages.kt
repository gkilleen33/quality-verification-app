package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.fundi.ui.terms.AgreementSection
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.WelcomeContent
import com.qualityverifier.ui.parts.WelcomeStep

/**
 * Fundi Bora's first launch (row 56, P17): what the app does, before anyone is asked to sign
 * in. "Sign in" and "I have an invite code" open today's routes. The wording is approved for
 * now (Nathan, 24 November 2026) and may change after he tries it.
 */
@Composable
fun FundiWelcomeScreen(onSignIn: () -> Unit, onInvite: () -> Unit) {
    val words = FundiScreenLabels.ENGLISH
    // WelcomeContent scrolls itself.
    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        WelcomeContent(
            wordmark = "Fundi Bora",
            title = words.welcomeTitle,
            howItWorks = words.welcomeHowItWorks,
            steps = listOf(
                WelcomeStep(words.welcomeStep1, words.welcomeStep1Line),
                WelcomeStep(words.welcomeStep2, words.welcomeStep2Line),
                WelcomeStep(words.welcomeStep3, words.welcomeStep3Line),
            ),
            primary = words.welcomeSignIn,
            onPrimary = onSignIn,
            secondary = words.welcomeInvite,
            onSecondary = onInvite,
        )
    }
}

/**
 * "Your agreement" from the Settings menu: "Sending your information to the United States",
 * with today's explanation and withdrawal ([AgreementSection]) unchanged, on its own page.
 */
@Composable
fun AgreementScreen(backLabel: String, onBack: () -> Unit, onWithdrawn: () -> Unit) {
    val words = FundiScreenLabels.ENGLISH
    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(words.menuAgreementLine)
            AgreementSection(onWithdrawn = onWithdrawn)
        }
    }
}
