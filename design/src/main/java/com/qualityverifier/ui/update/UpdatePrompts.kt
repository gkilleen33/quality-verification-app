package com.qualityverifier.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.qualityverifier.text.UpdateLabels

/*
 * The update banner and the update-required screen, shared by both apps.
 *
 * Here rather than in each app, because the two must say and do the same thing: one
 * Download button that opens the release page, and one rule for what a dismissal hides.
 * This module otherwise holds no screens; these two take their wording from UpdateLabels
 * and their state from the caller, and know nothing about which app draws them.
 */

/**
 * "A new version is available", with Download and a dismiss button. Shown on the home
 * screen when this build is behind the release. Dismissing hides it until the next start.
 */
@Composable
fun UpdateBanner(
    labels: UpdateLabels,
    downloadUrl: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(labels.newVersionAvailable, style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { context.openDownloadPage(downloadUrl) }) {
                    Text(labels.download)
                }
                TextButton(onClick = onDismiss) { Text(labels.dismiss) }
            }
        }
    }
}

/**
 * Shown in place of the whole app when this build is below the minimum the server serves.
 *
 * No way past it: the server answers this build's requests with 426, so anything behind
 * this screen would fail. Unreachable while the minimum is 1.
 */
@Composable
fun UpdateRequiredScreen(labels: UpdateLabels, downloadUrl: String) {
    val context = LocalContext.current
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            labels.updateRequiredTitle,
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center,
        )
        Text(
            labels.updateRequiredBody,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 16.dp, bottom = 24.dp),
        )
        Button(
            onClick = { context.openDownloadPage(downloadUrl) },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text(labels.download) }
    }
}

/**
 * Opens the release page in the browser. The page lists the APK; the browser downloads
 * it and Android offers to install it, which is how the pilot phones installed it first.
 */
private fun Context.openDownloadPage(url: String) {
    if (url.isBlank()) return
    try {
        startActivity(
            Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (e: ActivityNotFoundException) {
        // A phone with no browser. Nothing useful to show; the button does nothing.
        Log.w("UpdatePrompts", "No app can open $url", e)
    }
}
