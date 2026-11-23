package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.WorkPart
import com.qualityverifier.server.ReportFixtures.A_DAY
import com.qualityverifier.server.ReportFixtures.DAY_ONE
import com.qualityverifier.server.ReportFixtures.KAMAU
import com.qualityverifier.server.ReportFixtures.MAKER
import com.qualityverifier.server.ReportFixtures.MANAGER
import com.qualityverifier.server.ReportFixtures.OTIENO
import com.qualityverifier.server.ReportFixtures.PIECE_ID
import com.qualityverifier.server.ReportFixtures.STUDIO_ENTRY
import com.qualityverifier.server.ReportFixtures.session
import com.qualityverifier.server.ReportFixtures.sha
import com.qualityverifier.server.ReportFixtures.worker
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.LinkedWorker
import com.qualityverifier.server.db.PieceSessions
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
    // The maker's final assessment of the finished piece (V34, row 27), so the customer
    // report, which needs one, can be asked for.
    private val fixed = session("s2", DAY_ONE + A_DAY, listOf(ReportFixtures.ALL_FIXED), isFinal = true)

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
        val store = FakeReportStore(sessions = listOf(table, fixed))
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

    // The part and full kinds (plan v7 PR 4): what the request must carry, and which
    // assessment it names.
    @Test
    fun `a part report needs parts and takes a session, and the row records both`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table, fixed))
        val app = withReports(store)

        val response = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"part_issues","parts":["joinery"],"session_id":"s1"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val row = store.rows.single()
        assertEquals("part_issues", row.kind)
        assertEquals(listOf("joinery"), row.parts)
        assertEquals(listOf("s1"), row.sessionIds)
        assertTrue(row.printedNames.contains("Kamau"))
        assertTrue(row.defectSnapshot.contains("\"name\":\"Kamau\""))
        val text = ReportFixtures.text(response.bodyAsBytes())
        assertTrue(text, text.contains("Worked on this part: Kamau"))

        listOf(
            """{"kind":"part_issues"}""" to "parts_required",
            """{"kind":"part_issues","parts":[]}""" to "parts_required",
            """{"kind":"part_issues","parts":["legs"]}""" to "invalid_part",
            """{"kind":"full","parts":["joinery"]}""" to "parts_not_allowed",
        ).forEach { (body, code) ->
            val refused = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
                auth(MAKER); contentType(ContentType.Application.Json); setBody(body)
            }
            assertEquals(body, HttpStatusCode.BadRequest, refused.status)
            assertTrue(body, refused.bodyAsText().contains(code))
        }
        assertEquals(1, store.rows.size)

        // The newest listed assessment by default; a session of another piece is 404.
        val latest = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full") { auth(MAKER) }.bodyAsText()
        assertTrue(latest, latest.contains("\"session_ids\":[\"s2\"]"))
        val other = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full&session_id=elsewhere") { auth(MAKER) }
        assertEquals(HttpStatusCode.NotFound, other.status)
        assertTrue(other.bodyAsText().contains("no_such_assessment"))
    }

    @Test
    fun `a fixes report needs an earlier assessment with a verdict, deleted ones not counting`() = testApplication {
        val app = withReports(FakeReportStore(sessions = listOf(table)))
        val alone = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"part_fixes","parts":["joinery"]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, alone.status)
        assertTrue(alone.bodyAsText().contains("no_previous_assessment"))
    }

    @Test
    fun `a fixes report cannot name the first assessment, and covers the second`() = testApplication {
        val two = withReports(FakeReportStore(sessions = listOf(table, fixed)))
        val onFirst = two.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"part_fixes","parts":["joinery"],"session_id":"s1"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, onFirst.status)
        val onSecond = two.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"part_fixes","parts":["joinery"],"session_id":"s2"}""")
        }
        assertEquals(HttpStatusCode.Created, onSecond.status)
        assertTrue(ReportFixtures.text(onSecond.bodyAsBytes()).contains("Found this time"))
    }

    @Test
    fun `a client-deleted assessment cannot be chosen and does not count as the previous one`() = testApplication {
        // The first assessment client-deleted: the second has nothing before it, and the
        // deleted one cannot be named.
        val deleted = withReports(FakeReportStore(sessions = listOf(table.copy(clientDeleted = true), fixed)))
        val noPrevious = deleted.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"part_fixes","parts":["joinery"],"session_id":"s2"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, noPrevious.status)
        val named = deleted.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"full","session_id":"s1"}""")
        }
        assertEquals(HttpStatusCode.NotFound, named.status)
        // And the customer report still shows the deleted one.
        val customer = deleted.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer") { auth(MAKER) }.bodyAsText()
        assertTrue(customer, customer.contains("\"session_ids\":[\"s1\",\"s2\"]"))
    }

    @Test
    fun `the preview returns the names the report prints`() = testApplication {
        val app = withReports(FakeReportStore(sessions = listOf(table)))
        val body = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=part_issues&parts=joinery,finishing&session_id=s1") { auth(MAKER) }.bodyAsText()
        assertTrue(body, body.contains("\"type\":\"names\""))
        assertTrue(body, body.contains("\"names\":[\"Kamau\"]"))
        assertTrue(body, body.contains("Report made by Wanjirũ"))
    }

    // Part 3: "My manager" and the linked-worker report.
    @Test
    fun `a My manager report needs the piece labelled for a linked studio, and is the full report with the sender's name`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table, fixed))
        val app = withReports(store)

        // "My own job", or a label whose link does not hold: no manager to send it to.
        val refused = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"to_manager"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("no_linked_studio"))
        store.piece = store.piece.copy(studioEntryId = STUDIO_ENTRY)
        val unlinked = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"to_manager"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, unlinked.status)
        assertTrue(store.rows.isEmpty())

        store.piece = store.piece.copy(studioEntryId = STUDIO_ENTRY, managerId = MANAGER)
        val sent = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"to_manager","session_id":"s1","studio_id":"$STUDIO_ENTRY"}""")
        }
        assertEquals(HttpStatusCode.Created, sent.status)
        val text = ReportFixtures.text(sent.bodyAsBytes())
        assertTrue(text, text.contains("Report for my manager"))
        assertTrue(text, text.contains("Report made by Wanjirũ"))
        assertTrue(text, text.contains("Worked on this part: Kamau"))
        assertEquals("to_manager", store.rows.single().kind)
        assertEquals(listOf("s1"), store.rows.single().sessionIds)
    }

    @Test
    fun `a worker-work report is refused for a worker who is not linked, 404 for one not on the roster`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table)).apply { workers[KAMAU] = "Kamau" }
        val app = withReports(store)

        val unlinked = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"worker_work"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, unlinked.status)
        assertTrue(unlinked.bodyAsText().contains("worker_not_linked"))

        val stranger = app.post("/v1/fundi/workers/$OTIENO/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"worker_work"}""")
        }
        assertEquals(HttpStatusCode.NotFound, stranger.status)
        assertEquals(
            HttpStatusCode.Forbidden,
            app.get("/v1/fundi/workers/$KAMAU/reports/preview?kind=worker_work") { auth(MAKER) }.status,
        )
        // The kinds do not cross: a piece kind on a worker, a worker kind on a piece.
        val crossed = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"full"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, crossed.status)
        val onPiece = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"worker_work"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, onPiece.status)
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a worker-work report for a linked worker lists only the pieces they were named on`() = testApplication {
        val otherPiece = ReportFixtures.piece.copy(id = "c0ffee00-0000-4000-8000-00000000002a", label = "Hotel bench")
        val store = FakeReportStore(sessions = listOf(table, fixed)).apply {
            workers[KAMAU] = "Kamau"
            linked += KAMAU
            morePieces += PieceSessions(
                otherPiece,
                listOf(session("s9", DAY_ONE + 5 * A_DAY, ReportFixtures.TABLE, workers = listOf(worker(OTIENO, "Otieno", WorkPart.FINISHING)))),
            )
        }
        val app = withReports(store)

        val response = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"worker_work"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val text = ReportFixtures.text(response.bodyAsBytes())
        assertTrue(text, text.contains("Report on the work of Kamau"))
        assertTrue(text, text.contains("Kiti cha Wanjirũ"))
        assertFalse("a piece the worker was not named on", text.contains("Hotel bench"))
        assertTrue(text, text.contains("Worked on this part: Kamau"))
        assertTrue(text, text.contains("Gap at the back left joint"))
        assertFalse("an assessment the worker was not named on", text.contains("All put right"))
        val row = store.rows.single()
        assertEquals("worker_work", row.kind)
        assertEquals(KAMAU, row.workerId)
        assertEquals(null, row.pieceId)
        assertEquals(listOf("s1"), row.sessionIds)
        assertTrue(row.printedNames.contains("Kamau"))
    }

    // v0.6.0, PR 1: the studio label, the manager's access, and the final assessment.

    /** The maker's piece labelled for the studio [MANAGER] runs, with the link holding. */
    private val labelledPiece = ReportFixtures.piece.copy(studioEntryId = STUDIO_ENTRY, managerId = MANAGER)

    /** The manager's own assessment of that piece, the final one. */
    private val managersFinal = session("m1", DAY_ONE + 2 * A_DAY, listOf(ReportFixtures.ALL_FIXED), userId = MANAGER, isFinal = true)

    @Test
    fun `a customer report waits for the final assessment of the finished piece`() = testApplication {
        // No final assessment yet: refused with the code the phone acts on, and nothing written.
        val store = FakeReportStore(sessions = listOf(table))
        val waiting = withReports(store)
        val refused = waiting.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Conflict, refused.status)
        assertTrue(refused.bodyAsText().contains("final_assessment_required"))
        assertEquals(
            HttpStatusCode.Conflict,
            waiting.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer") { auth(MAKER) }.status,
        )
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `only the studio's own final assessment counts`() = testApplication {
        // The worker's own final flag, on a piece labelled for the studio, is not the studio's.
        val workersOwnFinal = table.copy(isFinal = true)
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(workersOwnFinal))
        val app = withReports(store)
        val notYet = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MANAGER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Conflict, notYet.status)
    }

    @Test
    fun `the studio's customer report includes the worker's assessments, prints no name, and is not the worker's to send`() = testApplication {
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(table, managersFinal))
        val app = withReports(store)

        // The worker made the piece but labelled it for the studio: the customer report is the studio's.
        val workers = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, workers.status)
        assertTrue(workers.bodyAsText().contains("customer_report_is_the_studios"))

        val response = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MANAGER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val row = store.rows.single()
        assertEquals("the worker's assessment and the manager's final one", listOf("s1", "m1"), row.sessionIds)
        assertTrue(row.printedNames.isEmpty())
        val text = ReportFixtures.text(response.bodyAsBytes())
        assertTrue(text, text.contains("Gap at the back left joint"))
        assertTrue(text, text.contains("Final check of the finished piece"))
        assertFalse(text, text.contains("Kamau"))
        assertFalse(text, text.contains("Report made by"))
    }

    // Row 51 (Nathan, 23 November 2026): a piece labelled for a studio with no linked manager,
    // such as a workshop whose owner does not use Fundi Bora. Its maker makes the final
    // assessment and sends the customer report, and the piece keeps its studio label.

    /** The maker's piece labelled for a studio entry no account has linked to. */
    private val unlinkedPiece = ReportFixtures.piece.copy(studioEntryId = STUDIO_ENTRY, managerId = null)

    /** The maker's own final assessment of that piece. */
    private val makersFinal = session("f1", DAY_ONE + 2 * A_DAY, listOf(ReportFixtures.ALL_FIXED), userId = MAKER, isFinal = true)

    @Test
    fun `row 51 - a maker with an unlinked studio is asked for the final assessment, not refused`() = testApplication {
        // The code the phone acts on by offering the final assessment, not "the studio's".
        val app = withReports(FakeReportStore(piece = unlinkedPiece, sessions = listOf(table)))
        val waiting = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Conflict, waiting.status)
        assertTrue(waiting.bodyAsText().contains("final_assessment_required"))
    }

    @Test
    fun `row 51 - a maker with an unlinked studio may send the customer report after their final assessment`() = testApplication {
        val store = FakeReportStore(piece = unlinkedPiece, sessions = listOf(table, makersFinal))
        val app = withReports(store)
        val sent = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Created, sent.status)
        assertEquals(listOf("s1", "f1"), store.rows.single().sessionIds)
        assertEquals("the piece keeps its studio label", STUDIO_ENTRY, store.piece.studioEntryId)
    }

    @Test
    fun `row 51 - once the studio is linked, only the manager's final assessment is headed Final check`() = testApplication {
        // The maker's final was made while the studio had no linked manager; the link formed later.
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(table, makersFinal, managersFinal))
        val app = withReports(store)
        val response = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MANAGER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val text = ReportFixtures.text(response.bodyAsBytes())
        assertEquals(text, 1, Regex("Final check of the finished piece").findAll(text).count())
    }

    @Test
    fun `row 51 - a maker whose studio manager is linked still may not send the customer report`() = testApplication {
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(table, makersFinal))
        val app = withReports(store)
        val refused = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"customer"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, refused.status)
        assertTrue(refused.bodyAsText().contains("customer_report_is_the_studios"))
        assertTrue(store.rows.isEmpty())
    }

    @Test
    fun `a manager reports on a linked worker's labelled piece, and the worker never reads the manager's assessment`() = testApplication {
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(table, managersFinal))
        val app = withReports(store)

        // The manager's one-assessment reports cover the manager's own: on the worker's, the
        // names recorded are the worker's roster, and "Me" would print the manager's name.
        val managers = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full") { auth(MANAGER) }.bodyAsText()
        assertTrue(managers, managers.contains("\"session_ids\":[\"m1\"]"))
        val workersOne = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full&session_id=s1") { auth(MANAGER) }
        assertEquals(HttpStatusCode.NotFound, workersOne.status)

        // The worker's newest is their own, and the manager's is not theirs to name.
        val worker = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full") { auth(MAKER) }.bodyAsText()
        assertTrue(worker, worker.contains("\"session_ids\":[\"s1\"]"))
        assertFalse(worker, worker.contains("All put right"))
        val managersOne = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full&session_id=m1") { auth(MAKER) }
        assertEquals(HttpStatusCode.NotFound, managersOne.status)
        val fixes = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=part_fixes&parts=joinery&session_id=s1") { auth(MAKER) }
        assertEquals("the manager's later assessment is not the worker's previous one", HttpStatusCode.BadRequest, fixes.status)
    }

    @Test
    fun `an assessment the worker deleted from their phone is not in the manager's customer report`() = testApplication {
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(table.copy(clientDeleted = true), managersFinal))
        val app = withReports(store)
        val body = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=customer") { auth(MANAGER) }.bodyAsText()
        assertTrue(body, body.contains("\"session_ids\":[\"m1\"]"))
    }

    @Test
    fun `a manager is refused an unlinked worker's piece, one labelled for another studio, and a My own job piece`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table, fixed))
        val app = withReports(store)
        listOf(
            ReportFixtures.piece.copy(studioEntryId = STUDIO_ENTRY, managerId = null),
            ReportFixtures.piece.copy(studioEntryId = STUDIO_ENTRY, managerId = "55555555-2222-3333-4444-555555555555"),
            ReportFixtures.piece.copy(studioEntryId = null, managerId = null),
        ).forEach { piece ->
            store.piece = piece
            val response = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=full") { auth(MANAGER) }
            assertEquals(piece.toString(), HttpStatusCode.NotFound, response.status)
            assertTrue(response.bodyAsText().contains("no_such_piece"))
        }
    }

    @Test
    fun `a My manager report goes only to the studio the piece is labelled for`() = testApplication {
        val store = FakeReportStore(piece = labelledPiece, sessions = listOf(table))
        val app = withReports(store)

        val elsewhere = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"to_manager","studio_id":"c0ffee00-0000-4000-8000-0000000000e9"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, elsewhere.status)
        assertTrue(elsewhere.bodyAsText().contains("not_the_pieces_studio"))

        // The manager cannot send the worker's "My manager" report to themselves either.
        val byManager = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MANAGER); contentType(ContentType.Application.Json); setBody("""{"kind":"to_manager"}""")
        }
        assertEquals(HttpStatusCode.Forbidden, byManager.status)

        // A studio_id on any other kind is a client bug.
        val misplaced = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"full","studio_id":"$STUDIO_ENTRY"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, misplaced.status)
        assertTrue(store.rows.isEmpty())

        val sent = app.post("/v1/fundi/pieces/$PIECE_ID/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"to_manager","studio_id":"$STUDIO_ENTRY"}""")
        }
        assertEquals(HttpStatusCode.Created, sent.status)
    }

    /**
     * The manager's roster entry for Kamau, linked to Kamau's own account, which made a
     * piece labelled for the manager's studio: a worker-work report and the worker's page.
     */
    private fun workerStore(): FakeReportStore {
        val theirPiece = ReportFixtures.piece.copy(
            id = THEIR_PIECE, userId = KAMAU_ACCOUNT, label = "Kamau's stool",
            studioEntryId = STUDIO_ENTRY, managerId = MAKER,
        )
        return FakeReportStore(sessions = listOf(table, fixed)).apply {
            workers[KAMAU] = "Kamau"
            linked += KAMAU
            accounts[KAMAU] = KAMAU_ACCOUNT
            labelled += PieceSessions(
                theirPiece,
                listOf(
                    // Kamau's own assessment on his phone.
                    session("w1", DAY_ONE + 4 * A_DAY, ReportFixtures.TABLE, userId = KAMAU_ACCOUNT),
                    // The manager's assessment of the same piece, naming Kamau on the whole piece.
                    session(
                        "m2", DAY_ONE + 5 * A_DAY, listOf(ReportFixtures.ALL_FIXED),
                        workers = listOf(worker(KAMAU, "Kamau", null)),
                    ),
                    // Another studio's manager, from before a label correction: never read here.
                    session("x1", DAY_ONE + 6 * A_DAY, listOf(ReportFixtures.ALL_FIXED), userId = MANAGER),
                ),
            )
        }
    }

    @Test
    fun `a worker-work report includes the worker's own assessments of labelled pieces, each piece once`() = testApplication {
        val store = workerStore()
        val app = withReports(store)

        val response = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"worker_work"}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val text = ReportFixtures.text(response.bodyAsBytes())
        assertEquals("each piece once", 1, Regex("Kamau's stool").findAll(text).count())
        assertTrue(text, text.contains("Their own assessment, made on their phone"))
        assertEquals(listOf("s1", "w1", "m2"), store.rows.single().sessionIds)
    }

    @Test
    fun `a worker-work report narrows to chosen pieces, and refuses a piece it does not cover`() = testApplication {
        val store = workerStore()
        val app = withReports(store)

        val narrowed = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"worker_work","piece_ids":["$THEIR_PIECE"]}""")
        }
        assertEquals(HttpStatusCode.Created, narrowed.status)
        assertEquals(listOf("w1", "m2"), store.rows.single().sessionIds)
        assertFalse(ReportFixtures.text(narrowed.bodyAsBytes()).contains("Kiti cha Wanjirũ"))

        val unknown = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json)
            setBody("""{"kind":"worker_work","piece_ids":["c0ffee00-0000-4000-8000-0000000000ff"]}""")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        val empty = app.post("/v1/fundi/workers/$KAMAU/reports") {
            auth(MAKER); contentType(ContentType.Application.Json); setBody("""{"kind":"worker_work","piece_ids":[]}""")
        }
        assertEquals(HttpStatusCode.BadRequest, empty.status)
        assertEquals(1, store.rows.size)
    }

    @Test
    fun `the worker's page lists their labelled pieces with what the manager may read, and no score`() = testApplication {
        val store = workerStore()
        val app = withReports(store)

        val body = app.get("/v1/fundi/workers/$KAMAU/pieces") { auth(MAKER) }.bodyAsText()
        val pieces = kotlinx.serialization.json.Json.decodeFromString(com.qualityverifier.domain.WorkerPieces.serializer(), body)
        val theirs = pieces.pieces.single { it.id == THEIR_PIECE }
        assertTrue(theirs.theirs)
        assertEquals(listOf("w1", "m2"), theirs.assessments.map { it.sessionId })
        assertEquals(listOf(true, false), theirs.assessments.map { it.byWorker })
        assertTrue(theirs.assessments.first().problems.any { it.title == "Gap at the back left joint" })
        assertFalse("the manager's own piece is not the worker's", pieces.pieces.single { it.id == PIECE_ID }.theirs)
        assertFalse(body, body.contains("score"))

        store.linked -= KAMAU
        assertEquals(HttpStatusCode.Forbidden, app.get("/v1/fundi/workers/$KAMAU/pieces") { auth(MAKER) }.status)
        assertEquals(HttpStatusCode.NotFound, app.get("/v1/fundi/workers/$OTIENO/pieces") { auth(MAKER) }.status)
    }

    @Test
    fun `whole-piece workers print under every chosen part of a part report`() = testApplication {
        val wholePiece = session(
            "s1", DAY_ONE, ReportFixtures.TABLE,
            workers = listOf(worker(KAMAU, "Kamau", null), worker(OTIENO, "Otieno", WorkPart.JOINERY)),
        )
        val app = withReports(FakeReportStore(sessions = listOf(wholePiece, fixed)))
        val body = app.get("/v1/fundi/pieces/$PIECE_ID/reports/preview?kind=part_issues&parts=finishing&session_id=s1") { auth(MAKER) }
            .bodyAsText()
        assertTrue("Kamau did the whole piece, finishing included: $body", body.contains("\"names\":[\"Kamau\"]"))
        assertFalse(body, body.contains("Otieno"))
    }

    @Test
    fun `no token is a 401`() = testApplication {
        val app = withReports(FakeReportStore(sessions = listOf(table)))
        assertEquals(HttpStatusCode.Unauthorized, app.post("/v1/fundi/pieces/$PIECE_ID/reports").status)
    }

    @Test
    fun `a second render while one is under way waits, then answers busy`() = testApplication {
        val store = FakeReportStore(sessions = listOf(table, fixed))
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

        /** The maker, or the manager the piece_managers view names: the SQL's rule. */
        override suspend fun piece(userId: String, pieceId: String): ReportPiece? =
            (listOf(piece) + labelled.map { it.piece })
                .firstOrNull { it.id == pieceId && (it.userId == userId || it.managerId == userId) }

        override suspend fun sessionsOf(pieceId: String): List<ReportSession> =
            ((if (pieceId == piece.id) sessions else emptyList()) +
                labelled.filter { it.piece.id == pieceId }.flatMap { it.sessions })
                .sortedBy { it.createdAt }

        /** A linked worker's own pieces labelled for a studio, with every account's sessions on them. */
        val labelled = mutableListOf<PieceSessions>()

        override suspend fun labelledPiecesOf(userId: String, workerAccountId: String): List<PieceSessions> =
            labelled.filter { it.piece.managerId == userId && it.piece.userId == workerAccountId }

        /** Each linked roster entry's own account, as the real store reads it through the match. */
        val accounts = mutableMapOf<String, String>()

        override suspend fun accountName(userId: String): String? = name

        /** The roster this fake holds: id to name; ids in [linked] hold the match. */
        val workers = mutableMapOf<String, String>()
        val linked = mutableSetOf<String>()
        /** The pieces a worker is named on, beyond [piece]: each with its sessions. */
        val morePieces = mutableListOf<PieceSessions>()

        override suspend fun linkedWorker(userId: String, workerId: String): LinkedWorker? =
            workers[workerId]?.takeIf { workerId in linked }?.let { LinkedWorker(workerId, it, accounts[workerId].orEmpty()) }
        override suspend fun ownsWorker(userId: String, workerId: String): Boolean = workerId in workers
        override suspend fun piecesNaming(userId: String, workerId: String): List<PieceSessions> =
            (listOf(PieceSessions(piece, sessions)) + morePieces)
                .filter { each -> each.sessions.any { s -> s.workers.any { it.workerId == workerId } } }

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
        /** Kamau's own Fundi Bora account, which the manager's roster entry KAMAU is linked to. */
        const val KAMAU_ACCOUNT = "44444444-2222-3333-4444-555555555555"
        const val THEIR_PIECE = "c0ffee00-0000-4000-8000-00000000002b"
    }
}
