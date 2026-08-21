package com.qualityverifier.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import com.qualityverifier.text.ReportLabels

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
