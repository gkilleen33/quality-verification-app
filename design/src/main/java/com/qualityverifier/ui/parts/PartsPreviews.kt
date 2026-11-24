package com.qualityverifier.ui.parts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qualityverifier.ui.theme.QualityVerifierTheme

// Each part as the mockups draw it, so it can be seen in Android Studio without signing in.
// The example words are the mockups' own.

@Composable
private fun Page(content: @Composable () -> Unit) {
    QualityVerifierTheme(darkTheme = false) {
        Column(Modifier.background(Parts.Page).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) { content() }
    }
}

@Preview(widthDp = 360, name = "A card of rows")
@Composable
private fun RowCardPreview() = Page {
    RowCard(heading = "My pieces") {
        CardRow("Daybed", line = "4 improvements · checked 20 Nov", onClick = {}, chip = Chip.new("New"))
        CardRow("Sofa frame", line = "4 improvements · checked 13 Nov", onClick = {}, divider = true)
        CardRow("All my pieces (5)", onClick = {}, divider = true, titleColor = Parts.Brand)
    }
    RowCard(heading = "About this piece") {
        CardRow("Name", line = "Daybed", onClick = {}, chip = Chip.neutral("Edit"), chevron = false)
        CardRow("Studio", line = "My own job", onClick = {}, chip = Chip.neutral("Edit"), chevron = false, divider = true)
    }
}

@Preview(widthDp = 360, name = "Radio rows and a greyed button")
@Composable
private fun RadioPreview() = Page {
    RowCard(heading = "What are you working on?") {
        CardRow("Table", radio = true, onClick = {})
        CardRow("Chair", radio = false, onClick = {}, divider = true)
    }
    PrimaryButton(
        "Start: take the photos",
        onClick = {},
        icon = Icons.Filled.PhotoCamera,
        state = ButtonState("Choose who it's for."),
    )
}

@Preview(widthDp = 360, name = "Status boxes")
@Composable
private fun StatusPreview() = Page {
    StatusBox(
        StatusKind.FAILURE,
        "Fundi Bora can't reach the server",
        "You're seeing the pieces saved on this phone, from 20 Nov at 10:14. Check your internet connection.",
        "Try again",
        {},
    )
    StatusBox(StatusKind.CONFIRMATION, "Report sent to the customer", "It is listed under Reports sent, below.", "OK", {})
    StatusBox(StatusKind.PROMPT, "Choose today's studio", "So your manager sees today's pieces.", "Choose", {})
}

@Preview(widthDp = 360, name = "Buttons, back bar, title, callout")
@Composable
private fun ButtonsPreview() = Page {
    BackBar("Home", onBack = {}, right = "Daybed")
    ScreenTitle("Daybed", line = "For: My own job")
    Callout("What it means for you", "The table may start to wobble within months of daily use.")
    PrimaryButton("Re-assess", onClick = {})
    SecondaryButton("Add a worker", onClick = {}, icon = Icons.Filled.Add)
}

@Preview(widthDp = 360, heightDp = 760, name = "Welcome")
@Composable
private fun WelcomePreview() = QualityVerifierTheme(darkTheme = false) {
    Column(Modifier.background(Parts.Page)) {
        WelcomeContent(
            wordmark = "Fundi Bora",
            title = "Check your furniture before you sell it",
            howItWorks = "How it works",
            steps = listOf(
                WelcomeStep("Take photos of a piece", "The app shows which photos to take."),
                WelcomeStep("Read what to fix", "What to improve first, and why customers care."),
                WelcomeStep("Send a report", "To a customer, a worker, or your manager."),
            ),
            primary = "Sign in",
            onPrimary = {},
            secondary = "I have an invite code",
            onSecondary = {},
        )
    }
}

@Preview(widthDp = 360, heightDp = 640, name = "Settings menu")
@Composable
private fun SettingsMenuPreview() = QualityVerifierTheme(darkTheme = false) {
    // The menu is a popup; drawn here as its panel's rows inside a card, which is how it looks.
    Column(Modifier.background(Parts.Page).padding(16.dp)) {
        RowCard(heading = "Settings") {
            CardRow("Change your password", onClick = {}, chevron = false)
            CardRow(
                "Record my location",
                line = "Each time you assess a piece, for research",
                onClick = {},
                chip = Chip.state(true, "On", "Off"),
                chevron = false,
                divider = true,
            )
            CardRow("Sign out", onClick = {}, chevron = false, divider = true)
            CardRow("Delete my account", onClick = {}, chevron = false, divider = true, titleColor = Parts.Danger)
        }
    }
}
