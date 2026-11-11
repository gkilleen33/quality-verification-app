package com.qualityverifier.update

import com.qualityverifier.data.update.AppIdentityInterceptor
import com.qualityverifier.data.update.AppVersionCheck
import com.qualityverifier.data.update.UpdateState
import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.UpdatePrompt
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * The update prompt, against a stand-in server.
 *
 * The version numbers are test values: an install at versionCode 4 with the server's
 * latest at 5, as in the v0.4.0 build plan's check.
 */
class AppVersionCheckTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private fun config(latest: Int, minimum: Int, url: String = "https://example.test/release") =
        MockResponse().setBody("""{"latest":$latest,"minimum":$minimum,"download_url":"$url"}""")

    private fun check(
        versionCode: Int = 4,
        enabled: Boolean = true,
        app: AppId = AppId.KAGUA,
    ) = AppVersionCheck(
        client = OkHttpClient.Builder()
            .addInterceptor(AppIdentityInterceptor(app, versionCode))
            .build(),
        baseUrl = server.url("/").toString(),
        json = json,
        appId = app,
        versionCode = versionCode,
        enabled = enabled,
    )

    @Test
    fun `below latest shows the banner, and dismissing it hides it`() = runTest {
        server.enqueue(config(latest = 5, minimum = 1))
        val check = check(versionCode = 4)

        check.refresh()
        assertEquals(UpdatePrompt.AVAILABLE, check.state.value.prompt)
        assertEquals("https://example.test/release", check.state.value.downloadUrl)

        check.dismissBanner()
        assertEquals(UpdatePrompt.NONE, check.state.value.prompt)
    }

    @Test
    fun `a dismissed banner stays dismissed when the check runs again after sign-in`() = runTest {
        server.enqueue(config(latest = 5, minimum = 1))
        server.enqueue(config(latest = 5, minimum = 1))
        val check = check(versionCode = 4)

        check.refresh()
        check.dismissBanner()
        check.refresh()

        assertEquals(UpdatePrompt.NONE, check.state.value.prompt)
    }

    @Test
    fun `below the minimum blocks, whatever was dismissed`() = runTest {
        server.enqueue(config(latest = 5, minimum = 1))
        server.enqueue(config(latest = 5, minimum = 5))
        val check = check(versionCode = 4)

        check.refresh()
        check.dismissBanner()
        check.refresh()

        assertEquals(UpdatePrompt.REQUIRED, check.state.value.prompt)
    }

    @Test
    fun `the request names the app and its build, and asks about that app`() = runTest {
        server.enqueue(config(latest = 1, minimum = 1))

        check(versionCode = 4, app = AppId.FUNDI).refresh()

        val recorded = server.takeRequest()
        assertEquals("/v1/auth/config?app=fundi", recorded.path)
        assertEquals("fundi", recorded.getHeader("X-App-Id"))
        assertEquals("4", recorded.getHeader("X-App-Version"))
    }

    @Test
    fun `a debug build sends nothing and shows nothing`() = runTest {
        val check = check(enabled = false)

        check.refresh()

        assertEquals(0, server.requestCount)
        assertEquals(UpdateState.NONE, check.state.value)
    }

    @Test
    fun `a failed check shows nothing rather than guessing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(503))
        val check = check()

        check.refresh()

        assertEquals(UpdateState.NONE, check.state.value)
    }

    @Test
    fun `an empty download url falls back to the public release page`() = runTest {
        server.enqueue(config(latest = 5, minimum = 1, url = ""))
        val check = check(versionCode = 4)

        check.refresh()

        assertEquals(
            "https://github.com/gkilleen33/quality-verification-app/releases/latest",
            check.state.value.downloadUrl,
        )
    }

    @Test
    fun `a 426 from any other route asks for the config again`() {
        server.enqueue(MockResponse().setResponseCode(426))
        server.enqueue(MockResponse().setResponseCode(426))
        val asked = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(AppIdentityInterceptor(AppId.KAGUA, 4) { asked.incrementAndGet() })
            .build()

        client.newCall(Request.Builder().url(server.url("/v1/chat")).build()).execute().close()
        // The config route is never refused, so a 426 there must not loop back into itself.
        client.newCall(Request.Builder().url(server.url("/v1/auth/config?app=kagua")).build())
            .execute().close()

        assertEquals(1, asked.get())
    }
}
