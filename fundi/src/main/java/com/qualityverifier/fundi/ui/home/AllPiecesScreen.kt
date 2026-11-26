package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle

/**
 * "All my pieces" (row 56), from Home's "All my pieces (N)": every piece, in two cards,
 * "In progress" and "Finished", newest first in each. [stageOf] says which; until a piece's
 * stage is stored (PR 5, row 55), it is the stage its latest assessment's purpose implies.
 */
@Composable
fun AllPiecesScreen(
    model: PiecesViewModel,
    backLabel: String,
    onBack: () -> Unit,
    onOpenPiece: (PieceSummary) -> Unit,
    stageOf: (PieceSummary) -> PieceStage = { PieceStage.impliedBy(it.newest.purpose) },
    now: () -> Long = System::currentTimeMillis,
) {
    val words = FundiScreenLabels.ENGLISH
    val pieces = model.pieces.collectAsState().value.orEmpty()
    val (inProgress, finished) = pieces.partition { stageOf(it) == PieceStage.IN_PROGRESS }
    val at = now()

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(words.allPieces)
            listOf(words.inProgress to inProgress, words.finished to finished).forEach { (heading, list) ->
                RowCard(heading = heading, hint = words.noneHere.takeIf { list.isEmpty() }) {
                    list.forEachIndexed { i, piece ->
                        CardRow(
                            pieceName(piece),
                            line = PieceLines.line(piece.newest),
                            chip = Chip.new(words.newTag).takeIf { PieceLines.isNew(piece, at) },
                            onClick = { onOpenPiece(piece) },
                            divider = i > 0,
                        )
                    }
                }
            }
        }
    }
}
