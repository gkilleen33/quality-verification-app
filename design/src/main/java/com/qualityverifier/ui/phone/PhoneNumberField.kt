package com.qualityverifier.ui.phone

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.domain.PhoneEntry
import com.qualityverifier.text.AuthLabels

/**
 * The phone number on every app's sign-up and sign-in screens and Fundi Bora's worker and
 * studio forms: a country code, +254 unless the person changes it, then the digits after it
 * (v0.7.1, row 47; it was KenyanPhoneField, +254 only).
 *
 * Here rather than in each app, like the update banner, because the apps must take a number
 * the same way. What the field keeps and sends is decided in [PhoneEntry] (and, after +254,
 * [KenyanPhone]); this only draws it and says why an edit was refused. Wording comes from
 * [AuthLabels]: Kagua passes the phone's language, Fundi Bora and PesaLog pass English.
 *
 * Send `entry.toE164()`, never the digits.
 *
 * The code sits in the field's leading slot as a button, "+254", always visible: tapping it
 * opens a box to type another code (Nathan, 24 November 2026: +254 stays the default, and a
 * person can tap it to type another). A pasted number with another country's code fills the
 * code and the digits, where the field used to refuse it as not Kenyan.
 *
 * "Too short" waits until the person leaves the field: shown while they are still typing,
 * it would sit under every number from the first digit to the eighth.
 *
 * The field holds a [TextFieldValue], not just the text, so it owns the cursor. With the
 * String overload, a refused edit kept the old text but took the refused edit's cursor:
 * a 0 typed in front of 12345678 left the cursor after the 1, and the next 7 landed
 * there. Callers still pass and receive only the [PhoneEntry].
 */
@Composable
fun PhoneNumberField(
    labels: AuthLabels,
    entry: PhoneEntry,
    onEntryChange: (PhoneEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refused by remember { mutableStateOf<KenyanPhone.Problem?>(null) }
    var focused by remember { mutableStateOf(false) }
    var choosingCode by remember { mutableStateOf(false) }
    val digits = entry.digits
    var held by remember { mutableStateOf(TextFieldValue(digits, TextRange(digits.length))) }

    // A screen can change the entry itself (clearing the form, a picked contact, restoring
    // after rotation); the cursor then goes to the end of what it set.
    val value = if (held.text == digits) held else TextFieldValue(digits, TextRange(digits.length))

    val standing = entry.problem
    val shown = refused ?: standing?.takeIf {
        it != KenyanPhone.Problem.TOO_SHORT || (digits.isNotEmpty() && !focused)
    }

    if (choosingCode) {
        CountryCodeDialog(
            labels = labels,
            current = entry.code,
            onUse = { code ->
                choosingCode = false
                refused = null
                // Digits that cannot be a Kenyan number are cleared on switching to +254,
                // never cut to nine digits nobody typed (PhoneEntry.withCode).
                val next = PhoneEntry.withCode(entry, code)
                if (next != entry) onEntryChange(next)
            },
            onDismiss = { choosingCode = false },
        )
    }

    OutlinedTextField(
        value = value,
        onValueChange = { proposed ->
            // Compose also calls this when only the cursor moves; that is not an edit, and
            // must not clear the message about the last one.
            if (proposed.text == value.text) {
                held = proposed
            } else {
                val edit = PhoneEntry.edit(entry.copy(digits = value.text), proposed.text)
                refused = edit.refused
                held = when (edit.entry.digits) {
                    // Taken as typed: keep the cursor where the keyboard put it.
                    proposed.text -> proposed
                    // Refused, or nothing but a dropped character such as +: put back the
                    // text and the cursor from before.
                    value.text -> value
                    else -> placeCursor(proposed, edit.entry.digits)
                }
                if (edit.entry != entry) onEntryChange(edit.entry)
            }
        },
        label = { Text(labels.phoneLabel) },
        leadingIcon = {
            CountryCodeButton(labels = labels, code = entry.code, onClick = { choosingCode = true })
        },
        supportingText = {
            Text(shown?.let { labels.phoneProblem(it, entry.kenyan) } ?: if (entry.kenyan) labels.phoneHint else labels.phoneHintAbroad)
        },
        isError = shown != null,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Phone,
            imeAction = ImeAction.Next,
        ),
        singleLine = true,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}

/**
 * "+254" in the field's leading slot. A button rather than the field's prefix: Material
 * shows a prefix only once the label has moved up, and a code that appears only after the
 * field is touched cannot be seen as something to tap.
 */
@Composable
private fun CountryCodeButton(labels: AuthLabels, code: String, onClick: () -> Unit) {
    val description = labels.phoneCodeDescription(code)
    Text(
        "+$code",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description }
            .padding(start = 16.dp, end = 4.dp, top = 14.dp, bottom = 14.dp),
    )
}

/** The box the country code is typed in. "Use this code" waits for a whole code. */
@Composable
private fun CountryCodeDialog(
    labels: AuthLabels,
    current: String,
    onUse: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var typed by remember { mutableStateOf(current) }
    val whole = PhoneEntry.isCountryCode(typed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.phoneCodeTitle) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(labels.phoneCodeBody, style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = PhoneEntry.editCode(it) },
                    prefix = { Text("+") },
                    label = { Text(labels.phoneCodeTitle) },
                    supportingText = { if (typed.isNotEmpty() && !whole) Text(labels.phoneBadCode) },
                    isError = typed.isNotEmpty() && !whole,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onUse(typed) }, enabled = whole) { Text(labels.phoneCodeUse) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.cancel) } },
    )
}

/**
 * Keeps a [PhoneEntry] across a recreated activity, for `rememberSaveable(stateSaver = …)`:
 * the worker and studio forms open the contacts app, and a low-memory phone recreates this
 * activity on the way back.
 */
val PhoneEntrySaver = listSaver<PhoneEntry, String>(
    save = { listOf(it.code, it.digits) },
    restore = { PhoneEntry(it[0], it[1]) },
)

/**
 * The cursor for an accepted edit that [PhoneEntry.edit] changed on the way in. When it
 * only dropped characters ("71-2", a paste with spaces), the cursor stays after the same
 * digit it followed. When it rewrote the number (a pasted "+254 0712…" cut to nine
 * digits), no digit lines up with the paste, so the cursor goes to the end.
 */
private fun placeCursor(proposed: TextFieldValue, digits: String): TextFieldValue {
    val onlyDropped = proposed.text.filter { it in '0'..'9' } == digits
    val cursor = if (onlyDropped) {
        proposed.text.take(proposed.selection.end).count { it in '0'..'9' }
    } else {
        digits.length
    }
    return TextFieldValue(digits, TextRange(cursor))
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PhoneNumberFieldKenyaPreview() {
    MaterialTheme {
        PhoneNumberField(AuthLabels.ENGLISH, PhoneEntry(digits = "712345678"), {}, Modifier.fillMaxWidth().padding(16.dp))
    }
}

@Preview(showBackground = true, widthDp = 360)
@Composable
private fun PhoneNumberFieldUgandaPreview() {
    MaterialTheme {
        PhoneNumberField(AuthLabels.ENGLISH, PhoneEntry("256", "772123456"), {}, Modifier.fillMaxWidth().padding(16.dp))
    }
}
