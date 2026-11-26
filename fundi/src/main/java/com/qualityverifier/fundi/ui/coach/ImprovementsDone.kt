package com.qualityverifier.fundi.ui.coach

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.SecondaryButton

/**
 * The end of an assessment's improvements (v0.7.1, release scope row 56; P17 in the Fundi Bora
 * v2 mockup): "Done" on the last improvement's page opens this rather than a greyed "Next
 * improvement". It says how many there were and what to do now, and offers the two next steps:
 * back to the piece's page, or Send report.
 *
 * Drawn over the conversation, as an improvement's page is. "All improvements" and the
 * system Back both return to the list, as they do from a page.
 */
@Composable
fun ImprovementsDone(
    count: Int,
    /** The piece as the lines name it: the maker's name for it, or its kind in lower case. */
    pieceName: String,
    labels: FundiLabels,
    onBackToList: () -> Unit,
    onBackToPiece: () -> Unit,
    onSendReport: () -> Unit,
) {
    BackHandler(onBack = onBackToList)
    // A Surface, as the improvement page is, so a tap anywhere on this screen stays on it and
    // never reaches the conversation drawn underneath.
    Surface(Modifier.fillMaxSize(), color = Parts.Page) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            BackBar(labels.allImprovements, onBackToList)
            Column(
                Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                ScreenTitle(labels.thatsAll(count), line = labels.fixThenReassess(count, pieceName))
                PrimaryButton(labels.backToPiece(pieceName), onClick = onBackToPiece)
                SecondaryButton(labels.sendReport, onClick = onSendReport)
            }
        }
    }
}
