package com.qualityverifier.ui.shop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.qualityverifier.data.sync.PasswordCheck
import com.qualityverifier.shop.NextCode
import com.qualityverifier.shop.ReportLinkState
import com.qualityverifier.shop.reportQr
import com.qualityverifier.text.AuthLabels
import com.qualityverifier.text.ReportLabels
import com.qualityverifier.ui.shopMode
import kotlinx.coroutines.launch
import kotlin.math.floor

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
 * "Send this report to my phone" on a shop's tablet (row 31), above Done. Pressed, it becomes
 * the QR code the customer scans with their own phone's camera, with what the code does and how
 * long it works. Outlined, not filled: Done stays the loudest thing on the screen (P8).
 *
 * Under the code, "Get a new code" replaces it with a fresh one, for a code that stopped
 * working before the customer had the PDF; the old code stays on screen while the new one
 * is fetched, and if it cannot be.
 */
@Composable
fun ShopSendReport(
    labels: ReportLabels,
    state: ReportLinkState,
    onRequest: () -> Unit,
    onRequestNew: () -> Unit,
) {
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (state is ReportLinkState.Shown) {
            ReportQrCode(
                url = state.url,
                description = labels.shopScanToDownload,
                modifier = Modifier.size(QR_SIZE),
            )
            Text(labels.shopScanToDownload, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            Text(
                labels.shopLinkLimits,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            when (state.next) {
                NextCode.NO_MORE -> Text(
                    labels.shopNoMoreCodes,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                else -> {
                    if (state.next == NextCode.FAILED) {
                        Text(labels.shopLinkFailed, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    val loading = state.next == NextCode.LOADING
                    // Quieter than the first button: not full width, so the code and Done
                    // stay what the eye goes to. 48 dp, Material's least touch target (P13).
                    OutlinedButton(
                        onClick = onRequestNew,
                        enabled = !loading,
                        modifier = Modifier.height(48.dp),
                    ) {
                        if (loading) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(labels.shopNewCode)
                        }
                    }
                }
            }
        } else if (state is ReportLinkState.NoMore) {
            Text(
                labels.shopNoMoreCodes,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        } else {
            val loading = state is ReportLinkState.Loading
            OutlinedButton(
                onClick = onRequest,
                enabled = !loading,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(labels.shopSendReport, style = MaterialTheme.typography.titleMedium)
                }
            }
            if (state is ReportLinkState.Failed) {
                Text(labels.shopLinkFailed, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The QR code for [url], black on white whatever the theme, since a camera reads contrast and
 * a dark theme would invert it. Each module is a whole number of pixels, so no hairline gaps
 * open between them for a scanner to misread.
 */
@Composable
fun ReportQrCode(url: String, description: String, modifier: Modifier = Modifier) {
    val matrix = remember(url) { reportQr(url) }
    Canvas(
        modifier
            .aspectRatio(1f)
            .background(Color.White)
            .semantics { contentDescription = description },
    ) {
        val cell = floor(size.minDimension / matrix.width)
        val origin = (size.minDimension - cell * matrix.width) / 2
        for (y in 0 until matrix.height) {
            var x = 0
            while (x < matrix.width) {
                if (!matrix.get(x, y)) {
                    x++
                    continue
                }
                // One rectangle per horizontal run of dark modules.
                val start = x
                while (x < matrix.width && matrix.get(x, y)) x++
                drawRect(
                    color = Color.Black,
                    topLeft = Offset(origin + start * cell, origin + y * cell),
                    size = Size((x - start) * cell, cell),
                )
            }
        }
    }
}

/** Large enough to scan from a phone held at arm's length over the tablet. */
private val QR_SIZE = 260.dp

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
