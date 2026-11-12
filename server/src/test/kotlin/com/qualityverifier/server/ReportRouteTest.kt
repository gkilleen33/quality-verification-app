package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.ReportFixtures.A_DAY
import com.qualityverifier.server.ReportFixtures.DAY_ONE
import com.qualityverifier.server.ReportFixtures.KAMAU
import com.qualityverifier.server.ReportFixtures.MAKER
import com.qualityverifier.server.ReportFixtures.PIECE_ID
import com.qualityverifier.server.ReportFixtures.session
import com.qualityverifier.server.ReportFixtures.sha
import com.qualityverifier.server.ReportFixtures.worker
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.ReportPiece
import com.qualityverifier.server.db.ReportRow
import com.qualityverifier.server.db.ReportSession
import com.qualityverifier.server.db.ReportStore
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.reports.PdfRenderer
import com.qualityverifier.server.reports.PhotoSource
import com.qualityverifier.server.reports.ReportCache
import com.qualityverifier.server.reports.ReportService
import com.qualityverifier.server.routes.REPORT_ID_HEADER
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The report routes (plan v7 §5.4): who may ask, what is refused, what the row records, and
 * that a retry is served from the cache with the same hash.
 */
class ReportRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val table = session(
        "s1", DAY_ONE, ReportFixtures.TABLE,
        photos = listOf(sha("p1"), sha("p2")),
        workers = listOf(worker(KAMAU, "Kamau", WorkPart.JOINERY)),
    )
    private val fixed = session("s2", DAY_ONE + A_DAY, listOf(ReportFixtures.ALL_FIXED))

    @Test
    fun `a fundi gets a PDF of their piece, and a row records it`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table, fixed))
        val app = withReports(store)

        val response = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"customer"}""")
        }

        assertEquals(HttpStatusCode.Created, response.status)
        assertEquals("application/pdf", response.headers[HttpHeaders.ContentType])
        val bytes = response.bodyAsBytes()
        assertEquals(bytes.size.toString(), response.headers[HttpHeaders.ContentLength])
        assertTrue(String(bytes.take(5).toByteArray()).startsWith("%PDF-"))

        val row = store.rows.single()
        assertEquals(response.headers[REPORT_ID_HEADER], store.ids.single())
        assertEquals("customer", row.kind)
        assertEquals(listOf("s1", "s2"), row.sessionIds)
        assertEquals(ReportCache.sha256(bytes), row.sha256)
        assertEquals(bytes.size, row.byteSize)
        assertEquals(listOf("s1-r1:0", "s1-r1:1", "s1-r1:2", "s1-r1:3"), row.defectRefs)
        assertTrue(row.defectSnapshot, row.defectSnapshot.contains("\"headline\":\"Strong table, one joint to close\""))
        assertFalse("no name on a customer report's row", row.defectSnapshot.contains("Kamau"))
        assertTrue(row.printedNames.isEmpty())
        assertEquals(null, row.parts)
        // And the customer report itself carries no name and no part.
        val text = ReportFixtures.text(bytes)
        assertFalse(text, text.contains("Kamau"))
        assertFalse(text, text.contains("Joinery"))
    }

    @Test
    fun `a second request with the same inputs is served from the cache with the same hash`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table))
        val renders = CountingRenderer()
        val app = withReports(store, renderer = renders)

        val first = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        val second = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }

        assertEquals(1, renders.count)
        assertTrue(first.bodyAsBytes().contentEquals(second.bodyAsBytes()))
        assertEquals(2, store.rows.size)
        assertEquals(store.rows[0].sha256, store.rows[1].sha256)

        // A renamed piece changes the inputs, so it renders again.
        store.piece = store.piece.copy(label = "Renamed")
        app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(2, renders.count)
    }

    @Test
    fun `the preview lists every assessment and the problems the report will print`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table, fixed))
        val app = withReports(store)

        val response = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer") { auth(MAKER) }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body, body.contains("\"kind\":\"customer\""))
        assertTrue(body, body.contains("\"session_ids\":[\"s1\",\"s2\"]"))
        assertTrue(body, body.contains("Gap at the back left joint"))
        assertTrue(body, body.contains("\"type\":\"problem\""))
        assertTrue(body, body.contains("\"photo_count\":2"))
        assertFalse(body, body.contains("Kamau"))
        assertTrue("nothing is written for a preview", store.rows.isEmpty())
    }

    @Test
    fun `a Kagua account is refused on both routes`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table))
        val buyer = withReports(store, audience = Audience.BUYER)

        val refused = buyer.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("not_a_fundi"))
        assertEquals(
            HttpStatusCode.Forbidden,
            buyer.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer") { auth(MAKER) }.status,
        )
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `another account's piece and a malformed id are 404`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table))
        val app = withReports(store)

        val stranger = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth("22222222-2222-3333-4444-555555555555"); contentType(ContentType.Application.Json)
            setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.NotFound, stranger.status)
        assertTrue(stranger.bodyAsText().contains("no_such_piece"))

        val malformed = app.post("/v1/fundi/pieces/not-a-uuid/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.NotFound, malformed.status)

        assertEquals(
            HttpStatusCode.NotFound,
            app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer") { auth("22222222-2222-3333-4444-555555555555") }.status,
        )
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `parts or a session on a customer report, and an unknown kind, are 400`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table))
        val app = withReports(store)

        listOf(
            """{"kind":"customer","parts":["joinery"]}""" to "parts_not_allowed",
            """{"kind":"customer","session_id":"s1"}""" to "session_not_allowed",
            """{"kind":"summary"}""" to "invalid_kind",
        ).forEach { (body, code) ->
            val response = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
                auth(MAKER); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(body, HttpStatusCode.BadRequest, response.status)
            assertTrue(body, response.bodyAsText().contains(code))
        }
        val preview = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer&parts=joinery") { auth(MAKER) }
        assertEquals(HttpStatusCode.BadRequest, preview.status)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `no token is a 401`() = testApplication {
        val app = withReports(FakeReportStore(sessions = listOf(table)))
        assertEquals(HttpStatusCode.Unauthorized, app.post("/v1/fundi/pieces/$PIECE_ID/reports").status)
    }

    @Test
    fun `a second render while one is under way waits, then answers busy`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table))
        val gate = CountDownLatch(1)
        val started = CountDownLatch(1)
        // The first render blocks on the photo read until the test lets it go.
        val slow = PhotoSource { sha ->
            started.countDown()
            gate.await(10, TimeUnit.SECONDS)
            if (sha == sha("p1") || sha == sha("p2")) ReportFixtures.PHONE_PHOTO else null
        }
        val app = withReports(store, photos = slow, waitForRender = 200)

        coroutineScope {
            // On IO: the test body's own thread must not be the one that blocks on the
            // latch, or the first request never starts.
            val first = async(Dispatchers.IO) {
                app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
                    auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
                }
            }
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            delay(50)
            val second = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
                auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
            }
            assertEquals(HttpStatusCode.ServiceUnavailable, second.status)
            assertTrue(second.bodyAsText().contains("busy"))

            gate.countDown()
            assertEquals(HttpStatusCode.Created, first.await().status)
        }
        assertEquals("only the render that finished wrote a row", 1, store.rows.size)
    }

    // ---------------------------------------------------------------- harness

    private fun ApplicationTestBuilder.withReports(
        store: FakeReportStore,
        audience: Audience = Audience.FUNDI,
        photos: PhotoSource = ReportFixtures.photosOf(sha("p1"), sha("p2")),
        renderer: PdfRenderer = PdfRenderer(),
        waitForRender: Long = 30_000,
    ) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(FakeAuth(audience), AccessTokens(KEY)),
                reports = Reports(
                    ReportService(
                        store = store,
                        photos = photos,
                        cache = ReportCache(folder.newFolder()),
                        renderer = renderer,
                        clock = { DAY_ONE + 3 * A_DAY },
                        waitForRender = waitForRender,
                    ),
                ),
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(userId: String) {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(userId).token}")
    }

    /** Counts renders, so a cache hit is visible as a render that did not happen. */
    private class CountingRenderer : PdfRenderer() {
        var count = 0
        override fun renderWithinCap(content: com.qualityverifier.server.reports.ReportContent, photos: PhotoSource): Rendered {
            count++
            return super.renderWithinCap(content, photos)
        }
    }

    class FakeReportStore(
        var piece: ReportPiece = ReportFixtures.piece,
        private val sessions: List<ReportSession>,
        private val name: String? = "Wanjirũ",
    ) : ReportStore {
        val rows = mutableListOf<ReportRow>()
        val ids = mutableListOf<String>()

        override suspend fun piece(userId: String, pieceId: String): ReportPiece? =
            piece.takeIf { it.id == pieceId && it.userId == userId }

        override suspend fun sessionsOf(pieceId: String): List<ReportSession> =
            sessions.filter { pieceId == piece.id }.sortedBy { it.createdAt }

        override suspend fun accountName(userId: String): String? = name

        override suspend fun record(row: ReportRow): String {
            rows += row
            val id = "d0000000-0000-4000-8000-${rows.size.toString().padStart(12, '0')}"
            ids += id
            return id
        }
    }

    private class FakeAuth(private val audience: Audience) : AuthStore {
        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = UserRow(
            userId, "Wanjirũ", "individual", null,
            disabled = false, isTester = false, audience = audience,
        )
        override suspend fun issueRefresh(
            userId: String, token: String, expiresAt: Instant, userAgent: String?, replaces: String?,
        ) = "r"
        override suspend fun findRefresh(token: String): StoredRefresh? = null
        override suspend fun revokeChain(userId: String) = 0
        override suspend fun credentialsForPhone(phone: String): Credentials? = null
        override suspend fun recordFailedSignIn(userId: String, lockFor: Duration, threshold: Int) = 0
        override suspend fun clearFailedSignIns(userId: String) = Unit
        override suspend fun passwordHashFor(userId: String): String? = null
        override suspend fun setPasswordHash(userId: String, passwordHash: String) = Unit
        override suspend fun markAccountDeleted(userId: String) = Unit
    }

    private companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
    }
}
