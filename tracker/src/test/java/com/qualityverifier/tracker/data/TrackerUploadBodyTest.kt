package com.qualityverifier.tracker.data

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/** What PesaLog 0.2.0 sends for each payment (row 32), against the real client and a stand-in server. */
class TrackerUploadBodyTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TrackerClient

    private object SignedIn : TokenStore {
        override fun accessToken() = "access"
        override fun refreshToken() = "refresh"
        override fun accessTokenExpiresAt() = Long.MAX_VALUE
        override fun userId() = "fundi"
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) = Unit
        override fun clear() = Unit
    }

    /** As TrackerContainer builds it: a null field is left out of the body. */
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
        client = TrackerClient(
            client = OkHttpClient(),
            tokens = TokenProvider(store = SignedIn, refresher = { RefreshOutcome.Rejected }),
            baseUrl = server.url("/").toString(),
            json = json,
        )
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun row(code: String, name: String?, phone: String?, masked: String?) = TransactionEntity(
        network = "mpesa", code = code, direction = "out", type = "send", amountKes = 500.0, feeKes = null,
        balanceAfterKes = null, occurredAt = 1_762_934_400_000, counterpartyName = name, counterpartyPhone = phone,
        parserVersion = 2, storedAt = 1_762_934_400_000, counterpartyMasked = masked,
    )

    @Test
    fun `row 32 - an upload carries the name beside the number, and the masked number as printed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":[],"rejected":[],"inserted":0}"""))

        client.upload(
            listOf(
                row("SFA1B2C3D5", name = "JANE DOE", phone = "254720456123", masked = null),
                row("UINRL7DIVX", name = "JANE DOE", phone = null, masked = "0720***123"),
            ),
        )

        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/tracker/transactions", request.path)
        val sent = Json.parseToJsonElement(request.body.readUtf8()).jsonObject["transactions"]!!.jsonArray.map { it.jsonObject }
        assertEquals("JANE DOE", sent[0]["counterparty_name"]!!.jsonPrimitive.content)
        assertEquals("254720456123", sent[0]["counterparty_phone"]!!.jsonPrimitive.content)
        // Nothing masked: the key is left out, so the body is one a server before V42 reads too.
        assertFalse(sent[0].containsKey("counterparty_masked"))
        assertEquals("JANE DOE", sent[1]["counterparty_name"]!!.jsonPrimitive.content)
        assertEquals("0720***123", sent[1]["counterparty_masked"]!!.jsonPrimitive.content)
        assertFalse(sent[1].containsKey("counterparty_phone"))
    }

    /**
     * A 0.2.0 phone against a server not yet deployed: the server's strict reader refuses the
     * new key with a 400. The rows stay on the phone for the next push, never marked as sent.
     */
    @Test
    fun `row 32 - a server before V42 refusing the masked field leaves the rows for later`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_request"}"""))

        val outcome = client.upload(listOf(row("UINRL7DIVX", name = "JANE DOE", phone = null, masked = "0720***123")))

        assertEquals(UploadOutcome.Later, outcome)
        assertTrue(server.takeRequest(1, TimeUnit.SECONDS) != null)
    }
}
