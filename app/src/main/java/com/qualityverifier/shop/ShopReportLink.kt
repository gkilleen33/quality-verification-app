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
    /** The QR code for [url], and the "Get a new code" button under it in state [next]. */
    data class Shown(val url: String, val next: NextCode = NextCode.READY) : ReportLinkState
    /** No link could be made; the button comes back with a line saying so. */
    data object Failed : ReportLinkState
    /** The server has made every link this assessment may have, and none is on screen. */
    data object NoMore : ReportLinkState
}

/**
 * "Get a new code" under a code already shown. The code stays on screen in every state: it
 * may still work, and a failed or refused request for another takes nothing away.
 */
enum class NextCode {
    /** The button, ready. */
    READY,
    LOADING,
    /** The last request failed; the button is back with a line saying so. */
    FAILED,
    /** The server's cap is reached; a line says no more codes can be made, and no button. */
    NO_MORE,
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
     * "Send this report to my phone": asks the server for a link and shows its code. Does
     * nothing while a request is out or once a code is on screen; [requestNew] replaces that.
     */
    fun request() {
        val now = _state.value
        if (now != ReportLinkState.Idle && now != ReportLinkState.Failed) return
        _state.value = ReportLinkState.Loading
        viewModelScope.launch {
            _state.value = when (val outcome = api.issueReportLink(sessionId)) {
                is ReportLinkOutcome.Ready -> ReportLinkState.Shown(outcome.url)
                ReportLinkOutcome.Failed -> ReportLinkState.Failed
                ReportLinkOutcome.NoMore -> ReportLinkState.NoMore
            }
        }
    }

    /**
     * "Get a new code": a fresh link in place of the one on screen. A code can stop working
     * before the customer has the PDF: a phone's browser may fetch it twice, or a second
     * person scans it, and three downloads are gone. The old link is left alone on the
     * server, to its own 30 minutes and three downloads. The server allows five links an
     * assessment and answers 429 after; the tablet then says no more can be made.
     */
    fun requestNew() {
        val shown = _state.value as? ReportLinkState.Shown ?: return
        if (shown.next == NextCode.LOADING || shown.next == NextCode.NO_MORE) return
        _state.value = shown.copy(next = NextCode.LOADING)
        viewModelScope.launch {
            _state.value = when (val outcome = api.issueReportLink(sessionId)) {
                is ReportLinkOutcome.Ready -> ReportLinkState.Shown(outcome.url)
                ReportLinkOutcome.Failed -> shown.copy(next = NextCode.FAILED)
                ReportLinkOutcome.NoMore -> shown.copy(next = NextCode.NO_MORE)
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
