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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
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

    val standing = KenyanPhone.problem(digits)
    val shown = refused ?: standing?.takeIf {
        it != KenyanPhone.Problem.TOO_SHORT || (digits.isNotEmpty() && !focused)
    }

    OutlinedTextField(
        value = digits,
        onValueChange = { proposed ->
            val edit = KenyanPhone.edit(digits, proposed)
            refused = edit.refused
            if (edit.digits != digits) onDigitsChange(edit.digits)
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
