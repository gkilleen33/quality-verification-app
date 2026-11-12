package com.qualityverifier.fundi.ui.workers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.ContactNumber
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.domain.StudioDetails
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.ui.phone.KenyanPhoneField

/**
 * The form for a studio this account works at (V31): the studio's name, and the studio
 * owner's number from the phone's contacts or the keyboard. The same picker and the same
 * number rules as the worker form ([WorkerForm]); the number is required here, since an
 * entry without one could never match.
 *
 * [current] null adds; otherwise the fields start as the entry is. [onArchive] is offered
 * only when editing an entry that is not yet archived.
 */
@Composable
fun StudioForm(
    labels: FundiLabels,
    current: StudioDetails?,
    onSave: (StudioDetails) -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null,
) {
    // Saveable, as the worker form's are: the contacts app comes in front of this
    // activity, and a low-memory phone recreates it on the way back.
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    var digits by rememberSaveable {
        mutableStateOf(current?.phone?.removePrefix(KenyanPhone.COUNTRY_CODE).orEmpty())
    }
    var pickProblem by remember { mutableStateOf<KenyanPhone.Problem?>(null) }
    var pickerFailed by remember { mutableStateOf(false) }

    val pick = rememberContactNumberPicker(
        onPicked = { pickedName, number ->
            pickerFailed = false
            val read = ContactNumber.read(number)
            pickProblem = read.refused
            digits = read.digits
            if (name.isBlank() && pickedName != null && WorkerNames.fits(pickedName)) name = pickedName
        },
        onUnavailable = { pickerFailed = true },
    )

    val phoneLabels = remember(labels) {
        AuthLabels.ENGLISH.copy(phoneLabel = labels.studioPhoneLabel, phoneHint = labels.workerPhoneHint)
    }
    val nameOk = WorkerNames.fits(name.trim()) && name.isNotBlank()
    val number = KenyanPhone.toE164(digits)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) labels.studioAddTitle else labels.studioEditTitle) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    // The same bound as a worker's name, which V31 keeps too.
                    onValueChange = { if (WorkerNames.fits(it)) name = it },
                    singleLine = true,
                    placeholder = { Text(labels.studioNameHint) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = pick, modifier = Modifier.fillMaxWidth()) {
                    Text(labels.workerChooseFromContacts)
                }
                if (pickerFailed) {
                    Text(
                        labels.workerContactsUnavailable,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                KenyanPhoneField(
                    labels = phoneLabels,
                    digits = digits,
                    onDigitsChange = {
                        digits = it
                        pickProblem = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                pickProblem?.let { problem ->
                    Text(
                        phoneLabels.phoneProblem(problem),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    labels.studioPhoneWhy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text(labels.studioArchive) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { number?.let { onSave(StudioDetails(name.trim(), it)) } },
                enabled = nameOk && number != null,
            ) { Text(labels.workerSave) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.workerCancel) } },
    )
}
