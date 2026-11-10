package com.qualityverifier.fundi.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qualityverifier.data.location.LocationPreference
import com.qualityverifier.text.FundiLabels

/**
 * The one question about a point per assessment, and the one place Android is asked.
 *
 * Asked at setup, and on Home for a maker who reached Home without answering it: an install
 * from before the question existed, a maker who skipped setup, or one who signed in on a
 * new phone (sign-in goes straight to Home). Both places use [rememberAssessmentLocationAnswer],
 * so the answer is stored and the permission requested the same way in each.
 *
 * The permission is requested here and from the switch in Settings, never when an
 * assessment starts. An assessment checks it silently and records nothing without it
 * (`LocationCapture.isAvailable`), so the second assessment never shows a prompt.
 */

/**
 * Stores [LocationPreference.answer] and, on a yes with no permission yet, asks Android.
 *
 * Stored before Android answers, not after. Somebody who says yes here and then refuses
 * Android's dialog has still said yes to us; they just record nothing until the permission
 * is given, and Settings says so beside the switch.
 */
@Composable
fun rememberAssessmentLocationAnswer(
    preference: LocationPreference,
    onAnswered: (Boolean) -> Unit = {},
): (Boolean) -> Unit {
    val context = LocalContext.current
    // Nothing to do with Android's answer: the next assessment reads the permission itself.
    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    return { yes ->
        preference.answer(yes)
        onAnswered(yes)
        if (yes && !context.hasLocationPermission()) {
            // Both, and coarse is enough: a fix good to a city block still says which
            // trading centre.
            request.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }
}

/**
 * The question as two chips, for the setup screen.
 *
 * Neither chip is selected until the maker chooses, even though the stored switch defaults
 * to on: an answer nobody gave is not consent.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AssessmentLocationQuestion(labels: FundiLabels, preference: LocationPreference) {
    var answer by remember {
        mutableStateOf(if (preference.answered) preference.records else null)
    }
    val onAnswer = rememberAssessmentLocationAnswer(preference) { answer = it }

    Text(labels.assessLocationQuestion, style = MaterialTheme.typography.titleSmall)
    Text(
        labels.assessLocationHint,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = answer == true,
            onClick = { onAnswer(true) },
            label = { Text(labels.assessLocationYes) },
        )
        FilterChip(
            selected = answer == false,
            onClick = { onAnswer(false) },
            label = { Text(labels.assessLocationNo) },
        )
    }
}

/**
 * The same question as a dialog, for Home.
 *
 * Shown while [LocationPreference.needsAnswer] is true, and it stays until the maker taps
 * one of the two answers: tapping outside does not count as either. Nothing is recorded
 * while it is unanswered, so leaving it open costs nothing.
 *
 * [onAnswer] comes from [rememberAssessmentLocationAnswer] in the screen that shows this,
 * not from inside the dialog: the dialog leaves the screen the moment it is answered, and
 * a permission request registered inside it would go with it.
 */
@Composable
fun AssessmentLocationDialog(labels: FundiLabels, onAnswer: (Boolean) -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(labels.assessLocationQuestion) },
        text = { Text(labels.assessLocationHint) },
        confirmButton = {
            TextButton(onClick = { onAnswer(true) }) { Text(labels.assessLocationYes) }
        },
        dismissButton = {
            TextButton(onClick = { onAnswer(false) }) { Text(labels.assessLocationNo) }
        },
    )
}

private fun Context.hasLocationPermission(): Boolean = listOf(
    Manifest.permission.ACCESS_FINE_LOCATION,
    Manifest.permission.ACCESS_COARSE_LOCATION,
).any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
