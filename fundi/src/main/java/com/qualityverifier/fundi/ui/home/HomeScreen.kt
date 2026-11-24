package com.qualityverifier.fundi.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.data.sync.SyncStatus
import com.qualityverifier.data.sync.SyncStep
import com.qualityverifier.domain.UpdatePrompt
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.fundi.ui.location.AssessmentLocationDialog
import com.qualityverifier.fundi.ui.location.rememberAssessmentLocationAnswer
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.text.UpdateLabels
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.PrimaryButton
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.SettingsMenuItem
import com.qualityverifier.ui.parts.StatusBox
import com.qualityverifier.ui.parts.StatusKind
import com.qualityverifier.ui.parts.Wordmark
import com.qualityverifier.ui.settings.AccountMenu
import com.qualityverifier.ui.settings.AccountMenuWords
import com.qualityverifier.ui.update.UpdateBanner

/**
 * Fundi Bora's Home (v0.7.1, rows 45, 46, 56), as `App_FundiBora_v2` draws it: the wordmark
 * and the gear, "Assess a new piece", a card "My pieces" with the newest three, and a card
 * "Your workshop".
 *
 * A red box replaces the old "last sync did not finish" line when the app cannot reach the
 * server (P19): it stays until a sync gets through, and "Try again" hides it while the retry
 * runs. It shows only when the server was not reached, not when one row was refused, which
 * "Not sent yet" on the piece already says.
 */
@Composable
fun HomeScreen(
    model: PiecesViewModel,
    onNewPiece: () -> Unit,
    onOpenPiece: (PieceSummary) -> Unit,
    onAllPieces: () -> Unit,
    onWorkers: () -> Unit,
    onStudios: () -> Unit,
    onChangePassword: () -> Unit,
    onAgreement: () -> Unit,
    onSignOut: () -> Unit,
    onAccountDeleted: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    val container = fundiContainer()
    val words = FundiScreenLabels.ENGLISH
    val loaded by model.pieces.collectAsState()
    val pieces = loaded.orEmpty()
    val roster by model.roster.collectAsState()
    val studios by model.studios.collectAsState()
    val update by container.appVersionCheck.state.collectAsState()
    val status by model.syncStatus.collectAsState()
    val syncing by model.syncing.collectAsState()
    var menuOpen by rememberSaveable { mutableStateOf(false) }

    // Once, for a maker who reached Home without answering the location question. Until they
    // answer, assessments record no point (LocationPreference.records).
    val locationPreference = container.locationPreference
    var askLocation by remember { mutableStateOf(locationPreference.needsAnswer) }
    val answerLocation = rememberAssessmentLocationAnswer(locationPreference) { askLocation = false }
    if (askLocation) AssessmentLocationDialog(FundiLabels.ENGLISH, answerLocation)

    if (menuOpen) {
        AccountMenu(
            container = container,
            labels = remember { FundiLabels.ENGLISH.onSettings(AuthLabels.ENGLISH) },
            words = AccountMenuWords(
                title = words.settings,
                changePassword = words.menuChangePassword,
                location = words.menuLocation,
                locationLine = words.menuLocationLine,
                on = words.menuOn,
                off = words.menuOff,
                signOut = words.menuSignOut,
                deleteAccount = words.menuDeleteAccount,
                close = words.menuClose,
            ),
            onDismiss = { menuOpen = false },
            onChangePassword = onChangePassword,
            onSignOut = onSignOut,
            onAccountDeleted = onAccountDeleted,
            extraItems = listOf(
                SettingsMenuItem(words.menuAgreement, line = words.menuAgreementLine) {
                    menuOpen = false
                    onAgreement()
                },
            ),
        )
    }

    LazyColumn(
        Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Wordmark("Fundi Bora", Modifier.weight(1f))
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.Settings, contentDescription = words.settings, tint = Parts.Brand, modifier = Modifier.size(26.dp))
                }
            }
        }
        // Above everything else, so it is seen; dismissible, so it is not in the way.
        if (update.prompt == UpdatePrompt.AVAILABLE) {
            item {
                UpdateBanner(UpdateLabels.ENGLISH, update.downloadUrl, container.appVersionCheck::dismissBanner)
            }
        }
        if (OfflineBox.shows(status, syncing)) {
            item {
                val since = status.lastSucceededAt
                StatusBox(
                    StatusKind.FAILURE,
                    words.offlineTitle,
                    words.offlineLine(since?.let(PieceLines::day), since?.let(PieceLines::time)),
                    words.tryAgain,
                    onButton = model::refresh,
                )
            }
        }
        item {
            PrimaryButton(
                PieceLines.assessLabel(words, loaded = loaded != null, pieces = pieces.size),
                onClick = onNewPiece,
                icon = Icons.Filled.PhotoCamera,
            )
        }
        item {
            if (loaded != null && pieces.isEmpty()) {
                RowCard(heading = words.myPieces, hint = words.myPiecesEmpty)
            } else {
                RowCard(heading = words.myPieces) {
                    val at = now()
                    pieces.take(PieceLines.HOME_COUNT).forEachIndexed { i, piece ->
                        CardRow(
                            pieceName(piece),
                            line = PieceLines.line(piece.newest),
                            chip = Chip.new(words.newTag).takeIf { PieceLines.isNew(piece, at) },
                            onClick = { onOpenPiece(piece) },
                            divider = i > 0,
                        )
                    }
                    if (PieceLines.showsAll(pieces.size)) {
                        CardRow(words.allPiecesCount(pieces.size), onClick = onAllPieces, divider = true, titleColor = Parts.Brand)
                    }
                }
            }
        }
        item {
            val active = roster.filter { !it.archived }
            RowCard(heading = words.yourWorkshop, modifier = Modifier.padding(bottom = 24.dp)) {
                CardRow(words.myWorkers, line = PieceLines.workersLine(active.size, active.count { it.linked }), onClick = onWorkers)
                CardRow(
                    words.studiosIWorkFor,
                    line = PieceLines.studiosLine(studios.count { !it.archived }),
                    onClick = onStudios,
                    divider = true,
                )
            }
        }
    }
}

/** The piece's own name, else the kind of thing it is. */
fun pieceName(piece: PieceSummary): String = piece.latest.pieceLabel ?: piece.latest.itemType.homeLabel

/** When Home shows the red box (row 56, P19). */
object OfflineBox {
    /**
     * The last sync did not reach the server, and no retry is running. A step the server
     * refused is not "can't reach the server"; the piece's "Not sent yet" covers it.
     */
    fun shows(status: SyncStatus, syncing: Boolean): Boolean =
        !syncing && status.failedAt != null && SyncStep.PULL in status.failed
}
