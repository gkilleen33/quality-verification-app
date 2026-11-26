package com.qualityverifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.qualityverifier.text.KaguaScreenLabels
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
 * The words of Kagua's v2 screens (v0.7.1, rows 45, 46 and 57): in [assessmentLanguage] on a
 * report and its problems, as the report's headings are, and otherwise in the phone's.
 */
@Composable
fun rememberKaguaLabels(assessmentLanguage: String? = null): KaguaScreenLabels {
    val deviceLanguage = LocalConfiguration.current.locales[0].language
    return remember(assessmentLanguage, deviceLanguage) {
        KaguaScreenLabels.forLanguage(assessmentLanguage?.takeIf { it.isNotBlank() } ?: deviceLanguage)
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
