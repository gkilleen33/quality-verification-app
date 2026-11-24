package com.qualityverifier.ui.shop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.qualityverifier.data.sync.PasswordCheck
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.shopMode
import kotlinx.coroutines.launch

/**
 * What a walk-in customer reads before each assessment on a shop's tablet (row 12).
 *
 * In English and Kiswahili at once, because it comes before the language question. Three
 * plain lines and one button. It promises only that the shop owner is not sent the
 * assessment: the tablet cannot stop an owner looking over the customer's shoulder.
 */
@Composable
fun ShopNoticeScreen(onStart: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        listOf(ReportLabels.ENGLISH, ReportLabels.SWAHILI).forEachIndexed { index, labels ->
            if (index > 0) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
            }
            Text(labels.shopNoticeHeading, style = MaterialTheme.typography.headlineSmall)
            listOf(labels.shopNoticeNotShared, labels.shopNoticeDeleted, labels.shopNoticeResearchers)
                .forEach { line -> Text(line, style = MaterialTheme.typography.bodyLarge) }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
        ) {
            Text("${ReportLabels.ENGLISH.shopNoticeStart} · ${ReportLabels.SWAHILI.shopNoticeStart}")
        }
    }
}

/**
 * The one button after the verdict on a shop's tablet (row 12): the customer is done, and the
 * tablet deletes its copy. Full width and filled, so it is the loudest thing on the screen.
 */
@Composable
fun ShopDoneButton(labels: ReportLabels, onDone: () -> Unit) {
    Button(
        onClick = onDone,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp),
    ) { Text(labels.shopDone, style = MaterialTheme.typography.titleMedium) }
}

/**
 * The account's password before the owner's controls open (row 12), so a customer cannot sign
 * the tablet out or delete the shop's account. Checked by the server, which counts a wrong
 * password as a failed sign-in.
 */
@Composable
fun OwnerPasswordDialog(
    labels: AuthLabels,
    check: suspend (String) -> PasswordCheck,
    onOpened: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    // A dialog is a window of its own, whose touches never pass MainActivity's touch handler,
    // and the on-screen keyboard's typing reaches neither. Both restart the idle minutes here,
    // so five minutes cannot pass, and close the dialog, while the owner is typing.
    val idle = shopMode().idle

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(labels.ownerPasswordTitle) },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.pointerInput(idle) {
                    awaitPointerEventScope {
                        while (true) {
                            // Initial pass: seen before the text field takes the touch.
                            awaitPointerEvent(PointerEventPass.Initial)
                            idle.touched()
                        }
                    }
                },
            ) {
                Text(labels.ownerPasswordBody)
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        problem = null
                        idle.touched()
                    },
                    label = { Text(labels.passwordLabel) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = problem != null,
                    supportingText = problem?.let { message -> { Text(message) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = password.isNotEmpty() && !checking,
                onClick = {
                    checking = true
                    scope.launch {
                        val outcome = check(password)
                        checking = false
                        when (outcome) {
                            PasswordCheck.MATCHES -> onOpened()
                            PasswordCheck.WRONG -> problem = labels.ownerPasswordWrong
                            PasswordCheck.LOCKED -> problem = labels.ownerPasswordLocked
                            PasswordCheck.UNAVAILABLE -> problem = labels.ownerPasswordUnavailable
                        }
                    }
                },
            ) { Text(labels.ownerOpen) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(labels.cancel) } },
    )
}
