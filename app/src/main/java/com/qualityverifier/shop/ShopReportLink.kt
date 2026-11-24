package com.qualityverifier.shop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.qualityverifier.data.sync.ReportLinkOutcome
import com.qualityverifier.data.sync.ShopReportLinkApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the result screen shows for "Send this report to my phone" (row 31). */
sealed interface ReportLinkState {
    /** The button, not yet pressed. */
    data object Idle : ReportLinkState
    data object Loading : ReportLinkState
    /** The QR code for [url]. */
    data class Shown(val url: String) : ReportLinkState
    /** No link could be made; the button comes back with a line saying so. */
    data object Failed : ReportLinkState
}

/**
 * The customer's PDF by QR code on a shop's tablet (release scope row 31; server V40).
 *
 * Holds the link for one assessment, in memory only. It is scoped to the assessment's screen,
 * so every end of the assessment (Done, leaving the app, the screen lock, five idle minutes)
 * clears it with the screen: the end puts back the start screen with nothing behind it, and
 * the code goes with the wipe. It is never written to the database, a file, or the screen's
 * saved state, so a restored screen cannot bring it back.
 */
class ShopReportLinkViewModel(
    private val api: ShopReportLinkApi,
    private val sessionId: String,
) : ViewModel() {

    private val _state = MutableStateFlow<ReportLinkState>(ReportLinkState.Idle)
    val state: StateFlow<ReportLinkState> = _state.asStateFlow()

    /**
     * Asks the server for a link and shows its code. Once a code is on screen another press
     * does nothing: the customer scans the one there, and the server allows five for each
     * assessment.
     */
    fun request() {
        val now = _state.value
        if (now is ReportLinkState.Loading || now is ReportLinkState.Shown) return
        _state.value = ReportLinkState.Loading
        viewModelScope.launch {
            _state.value = when (val outcome = api.issueReportLink(sessionId)) {
                is ReportLinkOutcome.Ready -> ReportLinkState.Shown(outcome.url)
                ReportLinkOutcome.Failed -> ReportLinkState.Failed
            }
        }
    }

    companion object {
        fun factory(api: ShopReportLinkApi, sessionId: String): ViewModelProvider.Factory = viewModelFactory {
            initializer { ShopReportLinkViewModel(api, sessionId) }
        }
    }
}

/**
 * The QR code for [content], quiet zone included: one entry per module, true for dark.
 *
 * ZXing, as the server's 2FA enrolment code is drawn (`admin/Qr.kt`), with the same settings:
 * error correction M, which a code read off a screen once does not need above, the four-module
 * quiet zone some scanners require, and ISO-8859-1, since the link is plain ASCII and a wider
 * character set would add a block older scanners handle badly.
 */
fun reportQr(content: String): BitMatrix = QRCodeWriter().encode(
    content,
    BarcodeFormat.QR_CODE,
    1,
    1,
    mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 4,
        EncodeHintType.CHARACTER_SET to "ISO-8859-1",
    ),
)
