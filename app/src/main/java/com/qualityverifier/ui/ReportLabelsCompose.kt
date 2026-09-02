package com.qualityverifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.text.TesterLabels

/**
 * Label set for an assessment written in [assessmentLanguage].
 *
 * Reads the device language through [LocalConfiguration] rather than
 * `Locale.getDefault()`, so that changing the phone's language recomposes the screen
 * instead of leaving stale headings behind until the next process start.
 */
@Composable
fun rememberReportLabels(assessmentLanguage: String?): ReportLabels {
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    return remember(assessmentLanguage, deviceLanguage) {
        ReportLabels.forLanguage(assessmentLanguage, deviceLanguage)
    }
}

/**
 * The evaluator questionnaire's wording, resolved the same observable way.
 *
 * `Locale.getDefault()` would read the language once and never notice it changing, which
 * lint flags for exactly that reason: a phone switched to Swahili mid-session would keep
 * showing an English form until the process restarted.
 */
@Composable
fun rememberTesterLabels(assessmentLanguage: String?): TesterLabels {
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    return remember(assessmentLanguage, deviceLanguage) {
        TesterLabels.forLanguage(assessmentLanguage, deviceLanguage)
    }
}
