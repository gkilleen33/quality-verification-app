package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.PieceStage
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.lineStyle

/**
 * "Is this piece finished?" from the Status row (row 55): two radio rows, Cancel and Save. It
 * leaves out the hint the intake gives with the same question ("If it is, you can send this
 * assessment to the customer in a report."): the status decides nothing about who may send
 * a customer report, which still needs a final assessment (row 27).
 */
@Composable
fun StageDialog(
    words: FundiScreenLabels,
    current: PieceStage,
    failed: Boolean,
    /** A save is on its way: Save is greyed, so a second tap cannot record the answer twice. */
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (PieceStage) -> Unit,
) {
    var picked by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(words.stageQuestion) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                CardRow(words.stageYes, radio = picked == PieceStage.FINISHED, onClick = { picked = PieceStage.FINISHED })
                CardRow(words.stageNo, radio = picked == PieceStage.IN_PROGRESS, onClick = { picked = PieceStage.IN_PROGRESS })
                if (failed) Text(words.stageFailed, style = lineStyle, color = Parts.Danger)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(picked) }, enabled = !saving) { Text(words.save, fontWeight = FontWeight.Bold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(words.cancel) } },
    )
}
