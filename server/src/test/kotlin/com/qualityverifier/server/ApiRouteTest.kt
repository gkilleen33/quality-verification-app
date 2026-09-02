package com.qualityverifier.server

import com.qualityverifier.server.api.ApiAssessmentDetail
import com.qualityverifier.server.api.ApiMessage
import com.qualityverifier.server.api.ApiTesterFeedback
import com.qualityverifier.server.api.PostgresApiKeyStore
import com.qualityverifier.server.blobs.BlobStore
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The read-only data API.
 *
 * A key here reads the whole corpus — phone numbers, locations, conversations and
 * photographs — with one string and no second factor. So most of what is worth testing is
 * the credential: that nothing works without it, that a revoked one stops working, and that
 * every request it makes leaves a record naming which key it was.
 */
class ApiRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    // ------------------------------------------------------------ the key

    @Test
    fun `every endpoint refuses a request with no key`() = testApplication {
        val app = withApi()

        listOf(
            "/api/v1/users",
            "/api/v1/assessments",
            "/api/v1/assessments/${API_ASSESSMENT.id}",
            "/api/v1/tester-feedback",
            "/api/v1/photos/${"a".repeat(64)}",
        ).forEach { path ->
            assertEquals(path, HttpStatusCode.Unauthorized, app.get(path).status)
        }
    }

    @Test
    fun `a made-up key is refused`() = testApplication {
        val app = withApi()

        val response = app.get("/api/v1/users") { header("X-API-Key", PostgresApiKeyStore.newSecret()) }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        // No hint about which part was wrong: a key is live or it is not.
        assertTrue(response.bodyAsText().contains("invalid_key"))
    }

    @Test
    fun `a revoked key stops working immediately`() = testApplication {
        // Revocation has to be checked on the request, not remembered from sign-in — there
        // is no session here to expire.
        val keys = FakeApiKeyStore()
        val secret = keys.anyKey()
        val app = withApi(keys = keys)
        assertEquals(HttpStatusCode.OK, app.get("/api/v1/users") { key(secret) }.status)

        keys.revoke(keys.issued.getValue(secret))

        assertEquals(HttpStatusCode.Unauthorized, app.get("/api/v1/users") { key(secret) }.status)
    }

    @Test
    fun `a key works as a bearer token or as X-API-Key`() = testApplication {
        // A research consumer is as likely to be a curl one-liner or an R script as a
        // library, and being turned away over a header name is a poor first minute.
        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys)

        assertEquals(
            HttpStatusCode.OK,
            app.get("/api/v1/users") { header("Authorization", "Bearer ${keys.anyKey()}") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            app.get("/api/v1/users") { header("X-API-Key", keys.anyKey()) }.status,
        )
    }

    @Test
    fun `every request is recorded against the key that made it`() = testApplication {
        // With a credential this broad, "somebody read everything" is a much less useful
        // record than "this key read this".
        val keys = FakeApiKeyStore()
        val admin = RecordingAudit()
        val app = withApi(keys = keys, admin = admin)

        app.get("/api/v1/assessments?testers=1") { key(keys.anyKey()) }

        val entry = admin.audits.single()
        assertEquals("api-read", entry.action)
        assertEquals("/api/v1/assessments", entry.target)
        assertTrue("the query is worth recording too", entry.detail!!.contains("testers=1"))
        assertTrue("the key must be named", entry.adminEmail.startsWith("api-key:"))
        // And the key's own row is touched, so an unused key is visible in the portal.
        assertEquals(listOf(keys.issued.getValue(keys.anyKey())), keys.used)
    }

    @Test
    fun `the API is read-only, so writes are not mounted at all`() = testApplication {
        // Structural rather than checked: only `get` is registered, so a leaked key cannot
        // alter anything even if a future route forgot to think about it.
        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys)

        val response = app.post("/api/v1/users") { key(keys.anyKey()) }

        assertTrue(
            "expected the method to be unsupported, got ${response.status}",
            response.status == HttpStatusCode.MethodNotAllowed ||
                response.status == HttpStatusCode.NotFound,
        )
    }

    // ------------------------------------------------------------- content

    @Test
    fun `a user comes back with identifiers and location intact`() = testApplication {
        // The API was chosen to expose these. If a change strips one, that is a silent
        // change to what the research can do, so it is pinned here.
        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys)

        val body = app.get("/api/v1/users") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(body, body.contains("+256700000000"))
        assertTrue(body, body.contains("Kampala Furniture"))
        assertTrue(body, body.contains("\"latitude\":0.3476"))
        assertTrue(body, body.contains("\"location_accuracy_m\":8.0"))
        assertTrue(body, body.contains("\"is_tester\":false"))
    }

    @Test
    fun `filters reach the store`() = testApplication {
        val keys = FakeApiKeyStore()
        val store = FakeApiStore()
        val app = withApi(keys = keys, store = store)

        app.get("/api/v1/assessments?testers=1&updated_since=1700000000000") { key(keys.anyKey()) }

        assertEquals(true, store.lastTestersOnly)
        assertEquals(1_700_000_000_000, store.lastUpdatedSince)
    }

    @Test
    fun `the page limit is capped however much is asked for`() = testApplication {
        // Otherwise limit=1000000 is a way to make one request take the box down, on an
        // instance that shares 3.7 GB with Postgres.
        val keys = FakeApiKeyStore()
        val store = FakeApiStore()
        val app = withApi(keys = keys, store = store)

        app.get("/api/v1/users?limit=100000") { key(keys.anyKey()) }

        // One over the cap, because the route asks for limit + 1 to detect another page.
        assertEquals(201, store.lastLimit)
    }

    @Test
    fun `an assessment comes back with its messages and photo hashes`() = testApplication {
        val keys = FakeApiKeyStore()
        val detail = ApiAssessmentDetail(
            assessment = API_ASSESSMENT,
            messages = listOf(
                ApiMessage("USER", "I am buying this.", 1L, listOf("aa".repeat(32))),
                ApiMessage("ASSISTANT", "A verdict.", 2L, emptyList()),
            ),
            feedback = ApiTesterFeedback(API_ASSESSMENT.id, "no", null, 5, 8, null),
        )
        val app = withApi(keys = keys, store = FakeApiStore(detail = detail))

        val body = app.get("/api/v1/assessments/${API_ASSESSMENT.id}") { key(keys.anyKey()) }
            .bodyAsText()

        assertTrue(body, body.contains("aa".repeat(32)))
        assertTrue(body, body.contains("I am buying this."))
        // The critique rides along, keyed on the same session id that is the merge key.
        assertTrue(body, body.contains("\"advice_stars\":5"))
    }

    @Test
    fun `an id that is not a uuid is a 404, not a 500`() = testApplication {
        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys)

        assertEquals(
            HttpStatusCode.NotFound,
            app.get("/api/v1/assessments/not-a-uuid") { key(keys.anyKey()) }.status,
        )
    }

    // -------------------------------------------------------------- photos

    @Test
    fun `a photo is served only when an assessment refers to it`() = testApplication {
        // The store is content-addressed, so the path parameter becomes a filename. Without
        // this check, "any 64 hex characters" is a request for any file in the blob
        // directory.
        val blobs = BlobStore(folder.newFolder())
        val bytes = "a photograph".toByteArray()
        val sha = BlobStore.hash(bytes)
        runBlocking { blobs.put(sha, bytes) }
        val orphan = BlobStore.hash("never referenced".toByteArray())
        runBlocking { blobs.put(orphan, "never referenced".toByteArray()) }

        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys, store = FakeApiStore(photos = setOf(sha)), blobs = blobs)

        assertEquals(
            HttpStatusCode.OK,
            app.get("/api/v1/photos/$sha") { key(keys.anyKey()) }.status,
        )
        assertEquals(
            "a file on disk that nothing references must not be readable",
            HttpStatusCode.NotFound,
            app.get("/api/v1/photos/$orphan") { key(keys.anyKey()) }.status,
        )
    }

    @Test
    fun `a hash-shaped non-hex path is refused before the disk is touched`() = testApplication {
        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys)

        assertEquals(
            HttpStatusCode.BadRequest,
            app.get("/api/v1/photos/${"z".repeat(64)}") { key(keys.anyKey()) }.status,
        )
    }

    @Test
    fun `responses are not cached`() = testApplication {
        // These carry phone numbers, locations and photographs. A proxy between a
        // researcher's laptop and here should not keep a page of that on disk.
        val keys = FakeApiKeyStore()
        val app = withApi(keys = keys)

        val headers = app.get("/api/v1/users") { key(keys.anyKey()) }.headers

        assertTrue(headers["Cache-Control"]!!.contains("no-store"))
        assertEquals("nosniff", headers["X-Content-Type-Options"])
    }

    // ---------------------------------------------------------------- harness

    private fun io.ktor.client.request.HttpRequestBuilder.key(secret: String) {
        header("X-API-Key", secret)
    }

    private fun ApplicationTestBuilder.withApi(
        keys: FakeApiKeyStore = FakeApiKeyStore(),
        store: FakeApiStore = FakeApiStore(),
        admin: RecordingAudit = RecordingAudit(),
        blobs: BlobStore = BlobStore(folder.newFolder()),
    ): HttpClient {
        application {
            module(
                version = "test",
                database = null,
                admin = Admin(
                    admin, blobs, NoFeedback, keys, store,
                    "a-signing-key-long-enough-to-be-real",
                    secureCookie = false,
                ),
            )
        }
        return createClient { }
    }
}
