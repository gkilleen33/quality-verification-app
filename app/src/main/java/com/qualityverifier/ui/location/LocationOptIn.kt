package com.qualityverifier.ui.location

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.ui.appContainer

/**
 * The switch that decides whether assessments record where they were made.
 *
 * One composable for both places it appears — the sign-up form and Settings — because the
 * offer has to read the same in both. A customer who agreed to something at sign-up should
 * find that same sentence when they go looking for it later, not a differently worded
 * version that leaves them guessing whether it is the same setting.
 *
 * Writes straight through to [com.qualityverifier.data.location.LocationPreference] on
 * every change rather than holding a draft. On the sign-up form that matters: somebody who
 * switches this off and then abandons the form at the password field has still said no,
 * and the next screen to ask should already know.
 *
 * ## The permission is a separate question from the setting
 *
 * The switch is the customer's answer. Android's permission is the system's, and the two
 * can disagree — a switch left on while location access was refused would read as
 * recording when nothing is. So when the setting is on and the permission is missing, this
 * says so and offers to ask.
 *
 * Deliberately not a dialog thrown at somebody the moment this screen opens. The setting
 * defaults to on, so an automatic request would be a permission prompt nobody asked for,
 * in front of a sentence they have not finished reading.
 */
@Composable
fun LocationOptIn(labels: AuthLabels, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val preference = appContainer().locationPreference

    var enabled by remember { mutableStateOf(preference.recordAtStart) }
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.ACCESS_FINE_LOCATION,
                ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val request = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        granted = results.values.any { it }
    }

    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                labels.recordLocationLabel,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(12.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { next ->
                    enabled = next
                    preference.recordAtStart = next
                },
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            labels.recordLocationHint,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (enabled && !granted) {
            Text(
                labels.locationPermissionNeeded,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(
                onClick = {
                    // Both, and coarse is enough: a fix good to a city block still says
                    // which trading centre, which is the question this data is for.
                    request.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    )
                },
            ) { Text(labels.allowLocationAccess) }
        }
    }
}
