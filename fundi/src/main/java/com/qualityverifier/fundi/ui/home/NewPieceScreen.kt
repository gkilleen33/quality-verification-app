package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.data.session.PieceStudioChoice
import com.qualityverifier.domain.ItemType
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.ButtonState
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.lineStyle
import androidx.compose.material3.Text

/** "My own job" in New piece's second card, kept apart from studio ids. */
private const val OWN_JOB = ""

/**
 * New piece (row 46, Raj Hansini's review): what the fundi is working on and who it is for,
 * on one screen, then "Start: take the photos". The studio question used to be a step of the
 * intake; it moves here, and the intake questions that follow stay as they were.
 *
 * Nothing is chosen at first (P25: a wrong label is never a default), and the start button
 * stays on the screen, greyed, saying what is missing (P19).
 *
 * [onStart] gets the type and the studio: [PieceStudioChoice.NotAsked] for an account with no
 * studio on its list, which is how the intake left such a piece before, so a studio added
 * later is the piece's first label rather than its one correction.
 */
@Composable
fun NewPieceScreen(
    backLabel: String,
    onBack: () -> Unit,
    onStart: (ItemType, PieceStudioChoice) -> Unit,
) {
    val container = fundiContainer()
    val words = FundiScreenLabels.ENGLISH
    val studios by container.studioRepository.observeStudios().collectAsState(initial = emptyList())
    val live = studios.filter { !it.archived }.sortedBy { it.name.lowercase() }
    var typeId by rememberSaveable { mutableStateOf<String?>(null) }
    var studioId by rememberSaveable { mutableStateOf<String?>(null) }
    val missing = PieceLines.newPieceMissing(typeId != null, studioId != null)

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(words.newPieceTitle)
            RowCard(heading = words.newPieceWhat) {
                // Every type Fundi Bora offered before, "Padded chair" included: Kagua's
                // homeChoices leaves it out because Kagua asks about upholstery at intake,
                // and Fundi Bora does not, so dropping it would change the plan Claude is sent.
                ItemType.entries.forEachIndexed { i, type ->
                    CardRow(type.homeLabel, radio = typeId == type.id, onClick = { typeId = type.id }, divider = i > 0)
                }
            }
            RowCard(heading = words.newPieceWho) {
                CardRow(words.forOwnJob, radio = studioId == OWN_JOB, onClick = { studioId = OWN_JOB })
                live.forEach { studio ->
                    CardRow(studio.name, radio = studioId == studio.id, onClick = { studioId = studio.id }, divider = true)
                }
            }
            // One sentence on who can see the assessments (Nathan, 16 November 2026), as the
            // intake's studio question said it.
            live.firstOrNull { it.id == studioId }?.let {
                Text(words.studioSees(it.name), style = lineStyle, color = Parts.Muted)
            }
            PrimaryButton(
                words.newPieceStart,
                icon = Icons.Filled.PhotoCamera,
                state = ButtonState(missing),
                onClick = {
                    val type = ItemType.fromId(typeId.orEmpty()) ?: return@PrimaryButton
                    val choice = when {
                        live.isEmpty() -> PieceStudioChoice.NotAsked
                        studioId == OWN_JOB -> PieceStudioChoice.OwnJob
                        else -> PieceStudioChoice.Studio(studioId.orEmpty())
                    }
                    onStart(type, choice)
                },
            )
        }
    }
}
