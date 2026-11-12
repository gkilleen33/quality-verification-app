package com.qualityverifier.server

import com.qualityverifier.server.api.ApiAssessmentDetail
import com.qualityverifier.server.api.ApiIntake
import com.qualityverifier.server.api.ApiMessage
import com.qualityverifier.server.api.ApiProblem
import com.qualityverifier.server.api.ApiSessionWorker
import com.qualityverifier.server.api.ApiTesterFeedback
import com.qualityverifier.server.api.PostgresApiKeyStore
import com.qualityverifier.server.api.apiProblemsOf
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
            "/api/v1/workers",
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
    fun `an assessment says which app build started it, and null when that is not known`() = testApplication {
        // V24. Null is an assessment from v0.3.0 or older, and it has to arrive as an
        // explicit null rather than a missing key or a zero, so an analysis can tell the
        // two apart from a build that never existed.
        val keys = FakeApiKeyStore()
        val store = FakeApiStore(
            assessments = listOf(
                API_ASSESSMENT.copy(appVersionCode = 4),
                API_ASSESSMENT.copy(id = "3ff77920-3928-46e5-8a77-5e16c1e901c6", appVersionCode = null),
            ),
        )
        val app = withApi(keys = keys, store = store)

        val body = app.get("/api/v1/assessments") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(body, body.contains("\"app_version_code\":4"))
        assertTrue(body, body.contains("\"app_version_code\":null"))
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
    fun `an assessment gives each photo's camera metadata beside its hash, and the list does not`() = testApplication {
        // V25. Entry n describes photo n; null is a photo sent without any, and has to arrive
        // as an explicit null so the positions stay aligned.
        val keys = FakeApiKeyStore()
        val meta = buildJsonObject {
            put("make", "samsung")
            put("gps_latitude", -1.2921)
            put("gps_longitude", 36.8219)
        }
        val detail = ApiAssessmentDetail(
            assessment = API_ASSESSMENT,
            messages = listOf(
                ApiMessage(
                    "USER", "Here they are.", 1L, listOf("aa".repeat(32), "bb".repeat(32)),
                    photoMeta = listOf(meta, null),
                ),
            ),
            feedback = null,
        )
        val store = FakeApiStore(assessments = listOf(API_ASSESSMENT), detail = detail)
        val app = withApi(keys = keys, store = store)

        val one = app.get("/api/v1/assessments/${API_ASSESSMENT.id}") { key(keys.anyKey()) }.bodyAsText()
        val list = app.get("/api/v1/assessments") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(
            one,
            one.contains("\"photo_meta\":[{\"make\":\"samsung\",\"gps_latitude\":-1.2921,\"gps_longitude\":36.8219},null]"),
        )
        assertTrue("the list route must not carry metadata: $list", !list.contains("photo_meta"))
        assertTrue(list, !list.contains("samsung"))
    }

    @Test
    fun `an assessment lists its problems, and the list route carries none`() = testApplication {
        // Problems are read from every reply, so they are on the single route only: on a
        // page of 200 they would mean reading every conversation.
        val keys = FakeApiKeyStore()
        val fundi = API_ASSESSMENT.copy(audience = "fundi", purpose = "evaluate", language = "en")
        val detail = ApiAssessmentDetail(
            assessment = fundi,
            messages = listOf(
                ApiMessage("USER", "Here is the table.", 1L, emptyList()),
                ApiMessage("ASSISTANT", TODAY_SHAPE_REPLY, 2L, emptyList()),
            ),
            feedback = null,
        )
        val store = FakeApiStore(assessments = listOf(fundi), detail = detail)
        val app = withApi(keys = keys, store = store)

        val one = app.get("/api/v1/assessments/${fundi.id}") { key(keys.anyKey()) }.bodyAsText()
        val list = app.get("/api/v1/assessments") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(
            one,
            one.contains(
                "\"problems\":[" +
                    "{\"title\":\"Gap at the back joint\",\"area\":\"structural\",\"severity\":\"moderate\"," +
                    "\"dimension\":\"joint_fit\",\"default_part\":\"joinery\"}," +
                    "{\"title\":\"Knot near the edge\",\"area\":\"material\",\"severity\":\"minor\"," +
                    "\"dimension\":null,\"default_part\":null}]",
            ),
        )
        assertTrue("the list route must not carry problems: $list", !list.contains("problems"))
        assertTrue(list, !list.contains("Gap at the back joint"))
    }

    @Test
    fun `the roster is listed with numbers, kinds and dates, paged and recorded like the rest`() = testApplication {
        // V30. The numbers belong to people with no account here, so they sit behind the
        // same key as the account holders' numbers, and the same audit row.
        val keys = FakeApiKeyStore()
        val admin = RecordingAudit()
        val store = FakeApiStore(
            workers = listOf(
                API_WORKER,
                API_WORKER.copy(
                    id = "c0ffee00-0000-4000-8000-00000000001c", name = null, phone = null,
                    employment = "employee", archivedAt = 1_700_000_300_000,
                ),
            ),
        )
        val app = withApi(keys = keys, store = store, admin = admin)

        val body = app.get("/api/v1/workers?limit=10") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(body, body.contains("\"id\":\"${API_WORKER.id}\""))
        assertTrue(body, body.contains("\"user_id\":\"${API_USER.id}\""))
        assertTrue(body, body.contains("\"name\":\"Kamau\""))
        assertTrue(body, body.contains("\"phone\":\"+254712345678\""))
        assertTrue(body, body.contains("\"employment\":\"temporary\""))
        assertTrue(body, body.contains("\"archived_at\":null"))
        assertTrue(body, body.contains("\"archived_at\":1700000300000"))
        // A deleted manager's roster: name and number null, as explicit nulls.
        assertTrue(body, body.contains("\"name\":null"))
        assertTrue(body, body.contains("\"phone\":null"))
        assertTrue(body, body.contains("\"created_at\":1700000000000"))
        assertTrue(body, body.contains("\"updated_at\":1700000200000"))
        assertTrue(body, body.contains("\"has_more\":false"))
        assertEquals(11, store.lastLimit)
        assertEquals("/api/v1/workers", admin.audits.single().target)
    }

    @Test
    fun `an assessment names who worked on it, by roster id and part`() = testApplication {
        // D9 in the design doc: the per-worker report needs this join, and the list route
        // never carried it.
        val keys = FakeApiKeyStore()
        val detail = ApiAssessmentDetail(
            assessment = API_ASSESSMENT.copy(audience = "fundi"),
            messages = emptyList(),
            feedback = null,
            workers = listOf(
                ApiSessionWorker(API_WORKER.id, "joinery"),
                // The manager, on the whole piece.
                ApiSessionWorker(null, null),
            ),
        )
        val app = withApi(keys = keys, store = FakeApiStore(detail = detail))

        val body = app.get("/api/v1/assessments/${API_ASSESSMENT.id}") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(
            body,
            body.contains(
                "\"workers\":[{\"worker_id\":\"${API_WORKER.id}\",\"part\":\"joinery\"}," +
                    "{\"worker_id\":null,\"part\":null}]",
            ),
        )
    }

    @Test
    fun `a diagnosis-only assessment lists its findings`() {
        val detail = ApiAssessmentDetail(
            assessment = API_ASSESSMENT.copy(audience = "fundi", purpose = "diagnose", language = "en"),
            messages = listOf(
                ApiMessage(
                    "ASSISTANT",
                    "```fb-diagnosis\n{\"findings\":[{\"title\":\"Drawer sticks\",\"area\":\"hardware\",\"severity\":\"minor\",\"dimension\":\"hardware\"}]}\n```",
                    1L, emptyList(),
                ),
            ),
            feedback = null,
        )
        val problems = apiProblemsOf(detail)!!
        assertEquals(listOf("Drawer sticks"), problems.map { it.title })
        assertEquals("assembly", problems.single().defaultPart)
    }

    @Test
    fun `problems that cannot be read arrive as null, and none found as an empty list`() {
        fun detailWith(reply: String) = ApiAssessmentDetail(
            assessment = API_ASSESSMENT.copy(audience = "fundi"),
            messages = listOf(ApiMessage("ASSISTANT", reply, 1L, emptyList())),
            feedback = null,
        )
        assertEquals(null, apiProblemsOf(detailWith("```fb-diagnosis\n{\"findings\":[{\"title\":\"Gap\n```")))
        assertEquals(emptyList<ApiProblem>(), apiProblemsOf(detailWith("Send me one more photo.")))
    }

    @Test
    fun `an assessment carries its purpose, language, piece and prompt, as explicit nulls when not known`() = testApplication {
        val keys = FakeApiKeyStore()
        val sha = "9f2e" + "0".repeat(60)
        val store = FakeApiStore(
            assessments = listOf(
                API_ASSESSMENT.copy(
                    audience = "fundi", purpose = "reevaluate", language = "sw",
                    pieceId = "5b0c6a2e-1f3d-4c4b-9a55-0d5f1c2e3a4b", promptSha = sha,
                ),
                API_ASSESSMENT.copy(id = "3ff77920-3928-46e5-8a77-5e16c1e901c6"),
            ),
        )
        val app = withApi(keys = keys, store = store)

        val body = app.get("/api/v1/assessments") { key(keys.anyKey()) }.bodyAsText()

        assertTrue(body, body.contains("\"purpose\":\"reevaluate\""))
        assertTrue(body, body.contains("\"language\":\"sw\""))
        assertTrue(body, body.contains("\"piece_id\":\"5b0c6a2e-1f3d-4c4b-9a55-0d5f1c2e3a4b\""))
        assertTrue(body, body.contains("\"prompt_sha\":\"$sha\""))
        assertTrue(body, body.contains("\"purpose\":null"))
        assertTrue(body, body.contains("\"language\":null"))
        assertTrue(body, body.contains("\"piece_id\":null"))
        assertTrue(body, body.contains("\"prompt_sha\":null"))
    }

    @Test
    fun `purpose and language decode from the intake code, and anything else is null`() {
        assertEquals(ApiIntake("evaluate", "en"), ApiIntake.of("fundi-en-evaluate"))
        assertEquals(ApiIntake("customer_return", "sw"), ApiIntake.of("fundi-sw-customer_return"))
        assertEquals(ApiIntake("work_in_progress", "en"), ApiIntake.of(" fundi-en-work_in_progress "))
        // Kagua's own code, an empty column, and malformed or unknown Fundi Bora codes.
        listOf(
            null, "", "buying|daily|full|en", "kagua-en-evaluate", "fundi-en", "fundi-fr-evaluate",
            "fundi-en-repaint", "fundi-en-evaluate-extra", "FUNDI-en-evaluate",
        ).forEach { code ->
            assertEquals(code.toString(), ApiIntake(null, null), ApiIntake.of(code))
        }
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

    private companion object {
        /** Today's shape: two defects, one with a finding of its title and one without. */
        val TODAY_SHAPE_REPLY = """
            One joint to close.

            ```fb-diagnosis
            {"findings":[{"title":"Gap at the back joint","area":"structural","severity":"moderate","dimension":"joint_fit"}]}
            ```

            ```qv-verdict
            {"verdict":"fair","headline":"One joint","defects":[
              {"title":"Gap at the back joint","area":"structural","severity":"moderate","what_i_see":"A gap."},
              {"title":"Knot near the edge","area":"material","severity":"minor","what_i_see":"A knot."}
            ]}
            ```
        """.trimIndent()
    }

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
