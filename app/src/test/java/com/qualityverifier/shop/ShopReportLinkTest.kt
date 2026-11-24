package com.qualityverifier.shop

import com.google.zxing.BinaryBitmap
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.qualityverifier.data.sync.ReportLinkOutcome
import com.qualityverifier.data.sync.ShopReportLinkApi
import com.qualityverifier.ui.chat.ChatControls
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The customer's PDF by QR code on a shop's tablet (release scope row 31), on the tablet's side. */
@OptIn(ExperimentalCoroutinesApi::class)
class ShopReportLinkTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private class Api(var outcome: ReportLinkOutcome) : ShopReportLinkApi {
        val asked = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun issueReportLink(sessionId: String): ReportLinkOutcome {
            asked += sessionId
            gate?.await()
            return outcome
        }
    }

    @Test
    fun `row 31 - the QR code holds the report link`() {
        val url = "https://kagua.gradykilleen.me/v1/r/AbCdEfGhIjKlMnOpQrSt_-"
        val matrix = reportQr(url)

        // Drawn as the tablet draws it, four pixels a module, and read back by a scanner.
        val scale = 4
        val side = matrix.width * scale
        val pixels = IntArray(side * side) { i ->
            if (matrix.get((i % side) / scale, (i / side) / scale)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val read = QRCodeReader().decode(BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, pixels))))

        assertEquals(url, read.text)
        // A small code: version 4 (33 modules) plus the quiet zone on each side.
        assertTrue("${matrix.width} modules", matrix.width <= 33 + 8)
    }

    @Test
    fun `row 31 - the tablet asks once for its assessment and shows the code it is given`() = runTest(dispatcher) {
        val api = Api(ReportLinkOutcome.Ready("https://kagua.gradykilleen.me/v1/r/token", expiresAt = 1L))
        api.gate = CompletableDeferred()
        val model = ShopReportLinkViewModel(api, "session-1")
        assertEquals(ReportLinkState.Idle, model.state.value)

        model.request()
        advanceUntilIdle()
        assertEquals(ReportLinkState.Loading, model.state.value)
        model.request() // a second press while it loads asks nothing more

        api.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(ReportLinkState.Shown("https://kagua.gradykilleen.me/v1/r/token"), model.state.value)
        model.request() // nor once the code is on screen
        advanceUntilIdle()
        assertEquals(listOf("session-1"), api.asked)
    }

    @Test
    fun `row 31 - a failed request says so and can be tried again`() = runTest(dispatcher) {
        val api = Api(ReportLinkOutcome.Failed)
        val model = ShopReportLinkViewModel(api, "session-1")

        model.request()
        advanceUntilIdle()
        assertEquals(ReportLinkState.Failed, model.state.value)

        api.outcome = ReportLinkOutcome.Ready("https://kagua.gradykilleen.me/v1/r/token", expiresAt = 1L)
        model.request()
        advanceUntilIdle()
        assertTrue(model.state.value is ReportLinkState.Shown)
        assertEquals(2, api.asked.size)
    }

    @Test
    fun `row 31 - Send this report shows on the tablet after the verdict, never on a personal phone`() {
        assertTrue(ChatControls.of(shopMode = true, hasVerdict = true, runActive = false, sending = false).sendReport)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = false, runActive = false, sending = false).sendReport)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = true, runActive = true, sending = false).sendReport)
        assertFalse(ChatControls.of(shopMode = true, hasVerdict = true, runActive = false, sending = true).sendReport)
        assertFalse(ChatControls.of(shopMode = false, hasVerdict = true, runActive = false, sending = false).sendReport)
    }
}
