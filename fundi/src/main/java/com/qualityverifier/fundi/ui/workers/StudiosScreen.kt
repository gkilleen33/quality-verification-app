package com.qualityverifier.fundi.ui.workers

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.qualityverifier.domain.Studio
import com.qualityverifier.domain.StudioDetails
import com.qualityverifier.fundi.ui.fundiContainer
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.text.FundiScreenLabels
import com.qualityverifier.ui.parts.BackBar
import com.qualityverifier.ui.parts.CardRow
import com.qualityverifier.ui.parts.Chip
import com.qualityverifier.ui.parts.Parts
import com.qualityverifier.ui.parts.RowCard
import com.qualityverifier.ui.parts.ScreenTitle
import com.qualityverifier.ui.parts.SecondaryButton

/**
 * "Studios I work for" (V31; v0.7.1, row 46): one card of rows, each a studio with the owner's
 * number and a chip, "Linked" or "Not linked", under "Your own workshop is already included.";
 * archived studios behind "Show archived (N)". Archived
 * studios are kept apart, below the list, behind "Show archived" (Nathan, 17 November
 * 2026), so the list holds only the studios in use. Add opens [StudioForm]; tapping a card
 * opens it to edit, with Archive. A card reads "Linked" when the server's two-sided match
 * holds: that studio's roster names this account's number too. Nothing else is shown about
 * the other side.
 */
@Composable
fun StudiosScreen(backLabel: String, onBack: () -> Unit) {
    val container = fundiContainer()
    val model: StudiosViewModel = viewModel(factory = StudiosViewModel.factory(container))
    val studios by model.list.collectAsState()
    val (active, archived) = remember(studios) {
        studios.sortedBy { it.name.lowercase() }.partition { !it.archived }
    }
    var showArchived by rememberSaveable { mutableStateOf(false) }
    val labels = FundiLabels.ENGLISH

    // Saveable: the contacts app comes in front, and the form must come back with the
    // activity. The edit holds the entry's id for the same reason.
    var adding by rememberSaveable { mutableStateOf(false) }
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = editingId?.let { id -> studios.firstOrNull { it.id == id } }

    if (adding) {
        StudioForm(
            labels = labels,
            current = null,
            onSave = { details ->
                adding = false
                model.add(details)
            },
            onDismiss = { adding = false },
            ownNumber = model.ownNumber,
        )
    }
    editing?.let { studio ->
        val before = StudioDetails(studio.name, studio.phone)
        StudioForm(
            labels = labels,
            current = before,
            onSave = { details ->
                editingId = null
                // Saved unchanged, nothing is written and nothing is sent.
                if (details != before) model.edit(studio.id, details)
            },
            onArchive = if (studio.archived) null else {
                {
                    editingId = null
                    model.archive(studio.id)
                }
            },
            onDismiss = { editingId = null },
            ownNumber = model.ownNumber,
        )
    }

    val words = FundiScreenLabels.ENGLISH

    // "Linked" is the server's word, from the last sync; "Archived" wins over it, since an
    // archived entry is never linked.
    @Composable
    fun row(studio: Studio, divider: Boolean) {
        CardRow(
            studio.name,
            line = words.owner(studio.phone),
            chip = when {
                studio.archived -> Chip.notLinked(words.archived)
                studio.linked -> Chip.linked(words.linked)
                else -> Chip.notLinked(words.notLinked)
            },
            onClick = { editingId = studio.id },
            divider = divider,
        )
    }

    Column(Modifier.fillMaxSize().background(Parts.Page).systemBarsPadding()) {
        BackBar(backLabel, onBack)
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ScreenTitle(words.studiosIWorkFor)
            SecondaryButton(words.addStudio, onClick = { adding = true })
            // The line under the list says the account's own workshop needs no entry (row 52).
            RowCard(hint = words.ownWorkshopIncluded) {
                active.forEachIndexed { i, studio -> row(studio, divider = true) }
                if (archived.isNotEmpty()) {
                    CardRow(
                        if (showArchived) labels.archivedHide else words.showArchived(archived.size),
                        onClick = { showArchived = !showArchived },
                        chevron = false,
                        titleColor = Parts.Brand,
                        divider = true,
                    )
                }
            }
            if (showArchived && archived.isNotEmpty()) {
                RowCard(heading = words.archived) {
                    archived.forEachIndexed { i, studio -> row(studio, divider = i > 0) }
                }
            }
        }
    }
}
