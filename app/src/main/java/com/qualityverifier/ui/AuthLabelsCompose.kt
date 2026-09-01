package com.qualityverifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.qualityverifier.text.AuthLabels

/**
 * The account screens' wording, in the phone's language.
 *
 * Read through LocalConfiguration rather than Locale.getDefault(): the latter is not
 * observable, so a language change would leave the screen in the old one until it happened
 * to recompose for another reason. Lint flags it, correctly.
 */
@Composable
fun rememberAuthLabels(): AuthLabels {
    val language = LocalConfiguration.current.locales[0].language
    return remember(language) { AuthLabels.forDevice(language) }
}
