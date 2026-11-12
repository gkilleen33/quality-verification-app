package com.qualityverifier.terms

import com.qualityverifier.data.terms.TermsRequiredInterceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TermsRequiredInterceptorTest {

    private lateinit var server: MockWebServer
    private var raised = 0

    private val client by lazy {
        OkHttpClient.Builder().addInterceptor(TermsRequiredInterceptor { raised++ }).build()
    }

    @Before
    fun setUp() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun get(): String = client.newCall(Request.Builder().url(server.url("/v1/fundi/chat")).build())
        .execute().use { it.body?.string().orEmpty() }

    @Test
    fun `a 403 terms_required raises the signal and leaves the body whole`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"terms_required"}"""))

        val body = get()

        assertEquals(1, raised)
        assertEquals("the caller still reads the body", """{"error":"terms_required"}""", body)
    }

    @Test
    fun `other refusals raise nothing`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"not_a_fundi"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"wrong_app"}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"terms_required"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"terms_required":true}"""))

        repeat(4) { get() }

        assertEquals(0, raised)
    }
}
