package com.qualityverifier.server

import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.AppVersionHeaders
import com.qualityverifier.domain.VersionPolicy
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `GET /v1/auth/config` and the 426 rule.
 *
 * The rule has one direction that matters more than the other: refusing a phone that
 * should have been served locks somebody out in a workshop with no way to tell why, so
 * every case that is not plainly "a known app below its minimum" is served.
 */
class AppVersionsTest {

    private val url = "https://github.com/gkilleen33/quality-verification-app/releases/tag/v0.4.0"

    @Test
    fun `with nothing configured the route answers 1 and 1`() = testApplication {
        application { module(version = "test", database = null) }

        val response = client.get("/v1/auth/config?app=kagua")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body, body.contains("\"latest\":1"))
        assertTrue(body, body.contains("\"minimum\":1"))
        assertTrue(body, body.contains(VersionPolicy.DEFAULT_DOWNLOAD_URL))
    }

    @Test
    fun `the route answers the configured values for the app asked about`() = testApplication {
        val versions = AppVersions.fromEnvironment(
            mapOf(
                "KAGUA_APP_LATEST_KAGUA" to "4",
                "KAGUA_APP_DOWNLOAD_URL_KAGUA" to url,
                "KAGUA_APP_LATEST_FUNDI" to "7",
            )::get,
        )
        application { module(version = "test", database = null, appVersions = versions) }

        val kagua = client.get("/v1/auth/config?app=kagua").bodyAsText()
        assertTrue(kagua, kagua.contains("\"latest\":4"))
        assertTrue(kagua, kagua.contains("\"minimum\":1"))
        assertTrue(kagua, kagua.contains("\"download_url\":\"$url\""))

        // Fundi Bora's own values, not Kagua's: both apps are at the same versionCode, so
        // one number for both would prompt the wrong app.
        val fundi = client.get("/v1/auth/config?app=fundi").bodyAsText()
        assertTrue(fundi, fundi.contains("\"latest\":7"))
        assertTrue(fundi, fundi.contains(VersionPolicy.DEFAULT_DOWNLOAD_URL))
    }

    @Test
    fun `the app can come from the header when the query does not name it`() = testApplication {
        val versions = AppVersions(mapOf(AppId.FUNDI to VersionPolicy(9, 1, url)))
        application { module(version = "test", database = null, appVersions = versions) }

        val body = client.get("/v1/auth/config") { header(AppVersionHeaders.APP, "fundi") }.bodyAsText()

        assertTrue(body, body.contains("\"latest\":9"))
    }

    @Test
    fun `an unknown app is a bad request`() = testApplication {
        application { module(version = "test", database = null) }

        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/auth/config?app=buyer").status)
        assertEquals(HttpStatusCode.BadRequest, client.get("/v1/auth/config").status)
    }

    @Test
    fun `an app below its minimum is refused with 426`() = testApplication {
        application { module(version = "test", database = null, appVersions = minimumFive()) }

        // /v1/me is not mounted here (no auth), so anything but 426 would be a 404. The
        // gate answering first is the point: it runs before routing and authentication.
        val response = client.get("/v1/me") {
            header(AppVersionHeaders.VERSION, "4")
            header(AppVersionHeaders.APP, "kagua")
        }

        assertEquals(426, response.status.value)
        assertTrue(response.bodyAsText().contains("upgrade_required"))
    }

    @Test
    fun `no version header is never refused`() = testApplication {
        application { module(version = "test", database = null, appVersions = minimumFive()) }

        val response = client.get("/v1/me") { header(AppVersionHeaders.APP, "kagua") }

        assertNotEquals(426, response.status.value)
    }

    @Test
    fun `a version with no readable app id is never refused`() = testApplication {
        application { module(version = "test", database = null, appVersions = minimumFive()) }

        val missing = client.get("/v1/me") { header(AppVersionHeaders.VERSION, "4") }
        val unknown = client.get("/v1/me") {
            header(AppVersionHeaders.VERSION, "4")
            header(AppVersionHeaders.APP, "somebody-else")
        }

        assertNotEquals(426, missing.status.value)
        assertNotEquals(426, unknown.status.value)
    }

    @Test
    fun `the other app's minimum does not apply`() = testApplication {
        // Kagua's minimum is 5; Fundi Bora's is unset.
        application { module(version = "test", database = null, appVersions = minimumFive()) }

        val response = client.get("/v1/me") {
            header(AppVersionHeaders.VERSION, "4")
            header(AppVersionHeaders.APP, "fundi")
        }

        assertNotEquals(426, response.status.value)
    }

    @Test
    fun `config, sign-in and health are served below the minimum`() = testApplication {
        application { module(version = "test", database = null, appVersions = minimumFive()) }

        fun io.ktor.client.request.HttpRequestBuilder.outdated() {
            header(AppVersionHeaders.VERSION, "4")
            header(AppVersionHeaders.APP, "kagua")
        }

        // The config route is how a blocked app learns it is blocked.
        val config = client.get("/v1/auth/config?app=kagua") { outdated() }
        assertEquals(HttpStatusCode.OK, config.status)
        assertTrue(config.bodyAsText().contains("\"minimum\":5"))

        assertEquals(HttpStatusCode.OK, client.get("/healthz") { outdated() }.status)
        assertNotEquals(426, client.get("/healthz/deep") { outdated() }.status.value)
        // Auth is not mounted in this test, so these answer 404; what matters is that the
        // gate let them through rather than answering 426.
        listOf("/v1/auth/sign-in", "/v1/auth/register", "/v1/auth/refresh").forEach { path ->
            assertNotEquals(path, 426, client.post(path) { outdated() }.status.value)
        }
    }

    @Test
    fun `with the minimum at 1 nothing is refused`() = testApplication {
        application { module(version = "test", database = null) }

        val response = client.get("/v1/me") {
            header(AppVersionHeaders.VERSION, "1")
            header(AppVersionHeaders.APP, "kagua")
        }

        assertNotEquals(426, response.status.value)
    }

    @Test
    fun `an unreadable number in the environment reads as 1, never higher`() {
        // A typo in the minimum must not lock every phone out.
        val versions = AppVersions.fromEnvironment(
            mapOf(
                "KAGUA_APP_MINIMUM_KAGUA" to "five",
                "KAGUA_APP_LATEST_KAGUA" to "-2",
                "KAGUA_APP_DOWNLOAD_URL_KAGUA" to "  ",
            )::get,
        )
        val kagua = versions.policyFor(AppId.KAGUA)
        assertEquals(1, kagua.minimum)
        assertEquals(1, kagua.latest)
        assertEquals(VersionPolicy.DEFAULT_DOWNLOAD_URL, kagua.downloadUrl)
    }

    @Test
    fun `a minimum above latest raises latest to match`() {
        val versions = AppVersions.fromEnvironment(
            mapOf("KAGUA_APP_MINIMUM_FUNDI" to "6", "KAGUA_APP_LATEST_FUNDI" to "4")::get,
        )
        val fundi = versions.policyFor(AppId.FUNDI)
        assertEquals(6, fundi.minimum)
        assertEquals(6, fundi.latest)
    }

    @Test
    fun `config reads the versions from the environment`() {
        val config = Config.fromEnvironment(mapOf("KAGUA_APP_LATEST_KAGUA" to "4")::get)
        assertEquals(4, config.appVersions.policyFor(AppId.KAGUA).latest)
        assertEquals(1, config.appVersions.policyFor(AppId.FUNDI).latest)
    }

    /** Kagua's minimum raised to 5; Fundi Bora's left unset. */
    private fun minimumFive() = AppVersions(
        mapOf(AppId.KAGUA to VersionPolicy(latest = 5, minimum = 5, downloadUrl = url)),
    )
}
