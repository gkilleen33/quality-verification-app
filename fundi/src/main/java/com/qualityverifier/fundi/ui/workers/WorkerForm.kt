package com.qualityverifier.fundi.ui.workers

import android.app.Activity
import android.content.Intent
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.ContactNumber
import com.qualityverifier.domain.Employment
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.domain.WorkerDetails
import com.qualityverifier.domain.WorkerNames
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.FundiLabels
import com.qualityverifier.ui.phone.KenyanPhoneField

/**
 * The one form for a roster entry: name, number, employee or temporary. Used to add a
 * worker from "My workers" and from inside "Who worked on this?", and to edit one, so
 * every place a worker is added offers the same three things (Nathan, 12 November 2026).
 *
 * The number is optional and comes from the phone's contacts or the keyboard. The picker
 * is the system's own ([Intent.ACTION_PICK] on a phone-number row), which hands back one
 * row with a read grant for that row alone: the app never asks for READ_CONTACTS and
 * never sees the rest of the address book. Only the row's number and display name are
 * read, and the name only fills an empty name field.
 *
 * A picked number goes through [ContactNumber], which applies the phone field's rules
 * ([KenyanPhone]) and says why a number cannot be used, in the field's own words; the
 * person can then type a correction or save without a number.
 *
 * [current] null adds; otherwise the fields start as the entry is. [onArchive] is offered
 * only when editing a worker who is not yet archived.
 */
@Composable
fun WorkerForm(
    labels: FundiLabels,
    current: WorkerDetails?,
    accepts: (String) -> Boolean,
    onSave: (WorkerDetails) -> Unit,
    onDismiss: () -> Unit,
    onArchive: (() -> Unit)? = null,
) {
    // A number stored by some other client in a form this field cannot hold (not +254).
    // Kept as it is unless a number is typed or picked over it, since clearing it unseen
    // would be a change the manager did not make.
    val foreign = current?.phone?.takeUnless { it.startsWith(KenyanPhone.COUNTRY_CODE) }

    // Saveable, not remembered: the contacts app comes in front of this activity, and a
    // low-memory phone recreates it on the way back. The form and its picker launcher
    // then have to come back with it, or the picked number is dropped unseen.
    var name by rememberSaveable { mutableStateOf(current?.name.orEmpty()) }
    // The field holds the nine digits, as the sign-in field does; the entry holds E.164.
    var digits by rememberSaveable {
        mutableStateOf(if (foreign == null) current?.phone?.removePrefix(KenyanPhone.COUNTRY_CODE).orEmpty() else "")
    }
    var employmentId by rememberSaveable { mutableStateOf((current?.employment ?: Employment.EMPLOYEE).id) }
    val employment = Employment.fromId(employmentId) ?: Employment.EMPLOYEE
    // Why the last picked number was not taken, shown until the number changes.
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
        AuthLabels.ENGLISH.copy(phoneLabel = labels.workerPhoneLabel, phoneHint = labels.workerPhoneHint)
    }
    // Saved with no number, or with a complete one; never with a half-typed one.
    val numberOk = digits.isEmpty() || KenyanPhone.toE164(digits) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) labels.workerAddTitle else labels.workerEditTitle) },
        text = {
            // Scrolls: with the keyboard up, four fields outgrow a small phone, and the
            // buttons below must stay reachable.
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    // Bounded as the server bounds it (V23), so a name is never refused later.
                    onValueChange = { if (WorkerNames.fits(it)) name = it },
                    singleLine = true,
                    placeholder = { Text(labels.workerNameHint) },
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
                // The field explains its own refusals; a picked number is refused here,
                // in the same words, because the field never saw the edit.
                pickProblem?.let { problem ->
                    Text(
                        phoneLabels.phoneProblem(problem),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    labels.workerPhoneWhy,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    labels.workerKindQuestion,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Employment.entries.forEach { kind ->
                        FilterChip(
                            selected = employment == kind,
                            onClick = { employmentId = kind.id },
                            label = { Text(labels.nameOf(kind)) },
                        )
                    }
                }
                if (onArchive != null) {
                    TextButton(onClick = onArchive) { Text(labels.workerArchive) }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // An empty field keeps a foreign number; anything typed replaces it.
                    val phone = KenyanPhone.toE164(digits) ?: foreign.takeIf { digits.isEmpty() }
                    onSave(WorkerDetails(name.trim(), phone, employment))
                },
                enabled = accepts(name) && numberOk,
            ) { Text(labels.workerSave) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.workerCancel) } },
    )
}

/**
 * Opens the system's phone-number picker and reads the one row the person taps.
 *
 * [Intent.ACTION_PICK] with the phone-number content type returns a URI for that row with
 * a temporary read grant, so no permission is declared or requested. [onPicked] gets the
 * row's display name (null when the contact has none) and its number as saved.
 * [onUnavailable] is called when no app answers the intent, or the row cannot be read.
 */
@Composable
internal fun rememberContactNumberPicker(
    onPicked: (name: String?, number: String) -> Unit,
    onUnavailable: () -> Unit,
): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        // Backing out of the picker is not a failure: nothing changes.
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        val read = runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) onPicked(cursor.getString(1), cursor.getString(0).orEmpty())
                true
            } ?: false
        }.getOrDefault(false)
        if (!read) onUnavailable()
    }
    return {
        val intent = Intent(Intent.ACTION_PICK).apply {
            type = ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE
        }
        // A phone with no contacts app throws here rather than returning a result.
        runCatching { launcher.launch(intent) }.onFailure { onUnavailable() }
    }
}
