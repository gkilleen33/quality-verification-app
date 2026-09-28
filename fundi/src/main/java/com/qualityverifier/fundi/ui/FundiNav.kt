package com.qualityverifier.fundi.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Where the producer app's screens will hang.
 *
 * A placeholder, and honest about it. The alternative was an empty module that builds a
 * blank APK and proves nothing; this one proves the whole chain — `:core`'s container
 * constructs against Fundi Bora's own `BuildConfig`, `:design`'s theme applies, the
 * manifest merges the permissions its libraries declare, and CI signs and publishes a
 * second APK. Those are the things that break when a module is added, and they break at
 * assembly time rather than in a screen.
 *
 * Auth and workshop setup land next, then the assessment loop.
 */
@Composable
fun FundiNav() {
    val container = fundiContainer()
    // Read once: nothing here signs in or out yet, so recomposing would not change it.
    val signedIn = remember { container.isSignedIn }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Fundi Bora", style = MaterialTheme.typography.displaySmall)
        // No tagline. Kagua has one from the brief; inventing a Swahili counterpart here
        // is exactly what issue #20 warns against, and a placeholder screen is a poor
        // reason to put unreviewed copy in front of a native speaker.
        Text(
            if (signedIn) {
                "Signed in. Workshop setup and assessments arrive in the next build."
            } else {
                "Not signed in yet. Sign-in, workshop setup and assessments arrive in " +
                    "the next build."
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 24.dp),
        )
    }
}
