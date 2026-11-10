package com.qualityverifier.ui.phone

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.text.AuthLabels

/**
 * The phone number on both apps' sign-up and sign-in screens: a fixed +254, then the nine
 * digits after it.
 *
 * Here rather than in each app, like the update banner, because the two must take a
 * number the same way. What the field keeps and sends is decided in [KenyanPhone]; this
 * only draws it and says why an edit was refused. Wording comes from [AuthLabels]: Kagua
 * passes the phone's language, Fundi Bora passes English, as the rest of its sign-in is.
 *
 * [digits] is the nine digits only. Send `KenyanPhone.toE164(digits)`, never [digits].
 *
 * The +254 is the text field's prefix, so it cannot be selected, deleted, or typed over.
 * Material shows a prefix only once the label has moved up, which happens as soon as
 * the field is touched.
 *
 * "Too short" waits until the person leaves the field: shown while they are still typing,
 * it would sit under every number from the first digit to the eighth.
 *
 * The field holds a [TextFieldValue], not just the text, so it owns the cursor. With the
 * String overload, a refused edit kept the old text but took the refused edit's cursor:
 * a 0 typed in front of 12345678 left the cursor after the 1, and the next 7 landed
 * there. Callers still pass and receive only the digits.
 */
@Composable
fun KenyanPhoneField(
    labels: AuthLabels,
    digits: String,
    onDigitsChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var refused by remember { mutableStateOf<KenyanPhone.Problem?>(null) }
    var focused by remember { mutableStateOf(false) }
    var held by remember { mutableStateOf(TextFieldValue(digits, TextRange(digits.length))) }

    // A screen can change the digits itself (clearing the form, restoring after rotation);
    // the cursor then goes to the end of what it set.
    val value = if (held.text == digits) held else TextFieldValue(digits, TextRange(digits.length))

    val standing = KenyanPhone.problem(digits)
    val shown = refused ?: standing?.takeIf {
        it != KenyanPhone.Problem.TOO_SHORT || (digits.isNotEmpty() && !focused)
    }

    OutlinedTextField(
        value = value,
        onValueChange = { proposed ->
            // Compose also calls this when only the cursor moves; that is not an edit, and
            // must not clear the message about the last one.
            if (proposed.text == value.text) {
                held = proposed
            } else {
                val edit = KenyanPhone.edit(value.text, proposed.text)
                refused = edit.refused
                held = when (edit.digits) {
                    // Taken as typed: keep the cursor where the keyboard put it.
                    proposed.text -> proposed
                    // Refused, or nothing but a dropped character such as +: put back the
                    // text and the cursor from before.
                    value.text -> value
                    else -> placeCursor(proposed, edit.digits)
                }
                if (edit.digits != digits) onDigitsChange(edit.digits)
            }
        },
        label = { Text(labels.phoneLabel) },
        prefix = { Text(KenyanPhone.COUNTRY_CODE + " ") },
        supportingText = { Text(shown?.let(labels::phoneProblem) ?: labels.phoneHint) },
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
 * The cursor for an accepted edit that [KenyanPhone.edit] changed on the way in. When it
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
