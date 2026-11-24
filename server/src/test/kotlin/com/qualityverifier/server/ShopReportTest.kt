package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.Credentials
import com.qualityverifier.server.db.RegisterOutcome
import com.qualityverifier.server.db.Registration
import com.qualityverifier.server.db.ShopReportLinkRow
import com.qualityverifier.server.db.ShopReportStore
import com.qualityverifier.server.db.StoredRefresh
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.reports.AddressRateLimit
import com.qualityverifier.server.reports.KaguaReportSession
import com.qualityverifier.server.reports.PdfRenderer
import com.qualityverifier.server.reports.ShopReportService
import com.qualityverifier.server.routes.PDF_NAME
import com.qualityverifier.server.routes.ReportLinkResponse
import com.qualityverifier.server.terms.TermsGate
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * The customer's PDF by QR code on a shop's tablet (release scope row 31, V40): the link the
 * tablet asks for, and the public download the customer's phone makes with it.
 */
class ShopReportTest {

    private var now = DAY_ONE

    @Test
    fun `row 31 - a token works three times and then refuses`() = testApplication {
        val world = World()
        val client = world.mount(this)
        val path = client.issue(SHOP, CURRENT).path

        repeat(3) { attempt ->
            val response = client.get("/$path")
            assertEquals("download ${attempt + 1}", HttpStatusCode.OK, response.status)
            assertEquals("application/pdf", response.headers[HttpHeaders.ContentType])
            assertTrue(String(response.bodyAsBytes().take(5).toByteArray()).startsWith("%PDF-"))
            assertTrue(response.headers[HttpHeaders.ContentDisposition].orEmpty().contains(PDF_NAME))
            assertEquals("no-store", response.headers[HttpHeaders.CacheControl])
            assertEquals("no-referrer", response.headers["Referrer-Policy"])
        }
        assertEquals(3, world.store.links.single().downloads)

        val fourth = client.get("/$path")
        assertEquals(HttpStatusCode.NotFound, fourth.status)
        assertTrue(fourth.bodyAsText().contains("This link has expired"))
        assertEquals("a refusal is not a download", 3, world.store.links.single().downloads)
    }

    @Test
    fun `row 31 - a token refuses after 30 minutes`() = testApplication {
        val world = World()
        val client = world.mount(this)
        val issued = client.issue(SHOP, CURRENT)
        assertEquals(DAY_ONE + 30 * MINUTE, issued.expiresAt)

        now = DAY_ONE + 30 * MINUTE - 1
        assertEquals("one millisecond before", HttpStatusCode.OK, client.get("/${issued.path}").status)

        now = DAY_ONE + 30 * MINUTE
        val expired = client.get("/${issued.path}")
        assertEquals(HttpStatusCode.NotFound, expired.status)
        assertTrue(expired.bodyAsText().contains("This link has expired"))
        assertEquals(1, world.store.links.single().downloads)
    }

    @Test
    fun `row 31 - a wrong token and a used token get the same answer`() = testApplication {
        val world = World()
        val client = world.mount(this)
        val used = client.issue(SHOP, CURRENT).path
        repeat(3) { client.get("/$used") }
        val expired = client.issue(SHOP, CURRENT).path
        now += 31 * MINUTE

        val answers = listOf(
            client.get("/$used"),
            client.get("/$expired"),
            // The right shape, never issued.
            client.get("/v1/r/" + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(16))),
            // Not even the right shape.
            client.get("/v1/r/nope"),
        )
        val first = answers.first()
        answers.forEach { answer ->
            assertEquals(first.status, answer.status)
            assertEquals(first.bodyAsText(), answer.bodyAsText())
            assertEquals(first.headers[HttpHeaders.ContentType], answer.headers[HttpHeaders.ContentType])
        }
        assertEquals(HttpStatusCode.NotFound, first.status)
        // In both languages, since a wrong token has no assessment to take a language from.
        val page = first.bodyAsText()
        assertTrue(page, page.contains("This link has expired"))
        assertTrue(page, page.contains("Kiungo hiki kimeisha muda wake"))
    }

    @Test
    fun `row 31 - the PDF holds no shop name, account name, or number`() = testApplication {
        val world = World()
        val client = world.mount(this)
        val path = client.issue(SHOP, CURRENT).path

        val pdf = client.get("/$path").bodyAsBytes()
        val text = ReportFixtures.text(pdf)
        ReportFixtures.save("kagua-shop-report-en.pdf", pdf)

        // It reads: the item, the verdict and its problems, what was not checked, the line.
        listOf(
            "Kagua report", "Item: Table", "Fair", "Solid frame, one loose leg", "What to look at",
            "Loose back leg", "It will wobble", "Not checked", "Underside of the top",
            "This is an independent check by Kagua.",
        ).forEach { assertTrue("missing \"$it\" in:\n$text", text.contains(it)) }
        // And nothing about the shop or its owner, though the account holds all three.
        listOf(SHOP_OWNER, SHOP_BUSINESS, SHOP_PHONE, SHOP_PHONE.removePrefix("+")).forEach {
            assertFalse("the PDF printed \"$it\"", text.contains(it))
        }
        assertFalse("a phone number in:\n$text", Regex("""(\+?254|\b0)[17]\d{8}""").containsMatchIn(text.replace(" ", "")))
    }

    @Test
    fun `row 31 - the route is rate-limited per address`() = testApplication {
        val world = World(limit = AddressRateLimit(limit = 3, windowMillis = 10 * MINUTE, clock = { now }))
        val client = world.mount(this)

        repeat(3) { client.get("/v1/r/nope") { header("X-Real-IP", "198.51.100.7") } }
        val fourth = client.get("/v1/r/nope") { header("X-Real-IP", "198.51.100.7") }
        assertEquals(HttpStatusCode.TooManyRequests, fourth.status)
        assertTrue(fourth.bodyAsText().contains("Too many tries"))

        // A real token from the same address is refused too: the limit comes first.
        val path = client.issue(SHOP, CURRENT).path
        assertEquals(HttpStatusCode.TooManyRequests, client.get("/$path") { header("X-Real-IP", "198.51.100.7") }.status)
        assertEquals(0, world.store.links.single().downloads)

        // Another address is not held up, and the first is let through once the window passes.
        assertEquals(HttpStatusCode.OK, client.get("/$path") { header("X-Real-IP", "203.0.113.9") }.status)
        now += 10 * MINUTE
        assertEquals(HttpStatusCode.OK, client.get("/$path") { header("X-Real-IP", "198.51.100.7") }.status)
    }

    @Test
    fun `row 31 - the limit keys on nginx's X-Real-IP, not on what a client puts in X-Forwarded-For`() = testApplication {
        val world = World(limit = AddressRateLimit(limit = 2, windowMillis = 10 * MINUTE, clock = { now }))
        val client = world.mount(this)

        // nginx appends the real address to X-Forwarded-For, so its first entry is the
        // client's to choose; a new one on every request must not buy a new allowance.
        repeat(2) { n ->
            client.get("/v1/r/nope") {
                header("X-Real-IP", "198.51.100.7")
                header("X-Forwarded-For", "10.0.0.$n, 198.51.100.7")
            }
        }
        val third = client.get("/v1/r/nope") {
            header("X-Real-IP", "198.51.100.7")
            header("X-Forwarded-For", "10.0.0.99, 198.51.100.7")
        }
        assertEquals(HttpStatusCode.TooManyRequests, third.status)
    }

    @Test
    fun `row 31 - only the shop tablet's assessment in progress gets a link`() = testApplication {
        val world = World()
        val client = world.mount(this)

        // A personal Kagua account has Share instead.
        val personal = client.post("/v1/sessions/$CURRENT/report-link") { auth(PERSONAL) }
        assertEquals(HttpStatusCode.Forbidden, personal.status)
        assertTrue(personal.bodyAsText().contains("shop_tablet_only"))

        // An earlier customer's assessment, and another account's, answer as an unknown id.
        listOf(EARLIER, OTHERS, "not-a-uuid").forEach { id ->
            val refused = client.post("/v1/sessions/$id/report-link") { auth(SHOP) }
            assertEquals(id, HttpStatusCode.NotFound, refused.status)
        }

        // The one in progress, before its verdict.
        world.store.sessions[CURRENT] = world.store.sessions.getValue(CURRENT).copy(replies = listOf("Take a photo of the whole table."))
        val early = client.post("/v1/sessions/$CURRENT/report-link") { auth(SHOP) }
        assertEquals(HttpStatusCode.Conflict, early.status)
        assertTrue(world.store.links.isEmpty())
    }

    @Test
    fun `row 31 - an assessment gets five links at most`() = testApplication {
        val world = World()
        val client = world.mount(this)
        repeat(ShopReportService.MAX_LINKS_PER_ASSESSMENT) { client.issue(SHOP, CURRENT) }
        val sixth = client.post("/v1/sessions/$CURRENT/report-link") { auth(SHOP) }
        assertEquals(HttpStatusCode.TooManyRequests, sixth.status)
        assertEquals(ShopReportService.MAX_LINKS_PER_ASSESSMENT, world.store.links.size)
    }

    @Test
    fun `row 31 - the server keeps only the token's hash, and the token has 128 bits`() = testApplication {
        val world = World()
        val client = world.mount(this)
        val issued = client.issue(SHOP, CURRENT)

        assertTrue(issued.path, issued.path.startsWith("v1/r/"))
        val token = issued.path.removePrefix("v1/r/")
        assertEquals(16, Base64.getUrlDecoder().decode(token).size)
        val row = world.store.links.single()
        assertEquals(ShopReportService.hashOf(token), row.tokenSha256)
        assertTrue(Regex("^[0-9a-f]{64}$").matches(row.tokenSha256))
        assertNotEquals(token, row.tokenSha256)
        assertEquals(SHOP, row.userId)
        assertEquals(CURRENT, row.sessionId)
        assertEquals(3, issued.maxDownloads)

        // Two links are two tokens.
        val second = client.issue(SHOP, CURRENT)
        assertNotEquals(issued.path, second.path)
    }

    @Test
    fun `row 31 - the PDF is in the language of the assessment`() = testApplication {
        val world = World()
        world.store.sessions[CURRENT] = world.store.sessions.getValue(CURRENT).copy(
            verdictLanguage = "sw",
            replies = listOf(verdict(language = "sw", headline = "Fremu imara, mguu mmoja unalegea")),
        )
        val client = world.mount(this)
        val pdf = client.get("/${client.issue(SHOP, CURRENT).path}").bodyAsBytes()
        val text = ReportFixtures.text(pdf)
        ReportFixtures.save("kagua-shop-report-sw.pdf", pdf)

        listOf("Ripoti ya Kagua", "Ya kuangalia", "Huu ni ukaguzi huru uliofanywa na Kagua.").forEach {
            assertTrue("missing \"$it\" in:\n$text", text.contains(it))
        }
        assertFalse(text, text.contains("What to look at"))
    }

    @Test
    fun `row 31 - a follow-up after the code was shown is in the next download`() = testApplication {
        val world = World()
        val client = world.mount(this)
        val path = client.issue(SHOP, CURRENT).path
        assertTrue(ReportFixtures.text(client.get("/$path").bodyAsBytes()).contains("Solid frame, one loose leg"))

        world.store.sessions[CURRENT] = world.store.sessions.getValue(CURRENT).let {
            it.copy(replies = it.replies + verdict(headline = "Sound once the leg is glued"))
        }
        assertTrue(ReportFixtures.text(client.get("/$path").bodyAsBytes()).contains("Sound once the leg is glued"))
    }

    @Test
    fun `row 31 - the link's limits match V40`() {
        val sql = File("db/migrations/V40__shop_report_links.sql").readText()
        assertEquals(30L * 60 * 1000, ShopReportService.LINK_LIFETIME_MILLIS)
        assertTrue("V40 does not hold expires_at to 30 minutes", sql.contains("expires_at <= created_at + interval '30 minutes'"))
        assertTrue(
            "V40 does not hold downloads to MAX_DOWNLOADS",
            sql.contains("CHECK (cardinality(downloaded_at) <= ${ShopReportService.MAX_DOWNLOADS})"),
        )
        assertTrue("V40 does not keep the hash as lowercase hex", sql.contains("CHECK (token_sha256 ~ '^[0-9a-f]{64}$')"))
        assertFalse("V40 stores a token column", Regex("""(?i)\btoken\s+(text|varchar|char)""").containsMatchIn(sql))
    }

    @Test
    fun `row 31 - no log line carries a report token`() {
        val token = "AAAAAAAAAAAAAAAAAAAAAA"
        assertEquals("/v1/r/…", loggablePath("/v1/r/$token"))
        assertEquals("/v1/r/…", loggablePath("/v1/%72/$token"))
        assertEquals("/v1/sessions/abc", loggablePath("/v1/sessions/abc"))
    }

    @Test
    fun `row 31 - the download is on the terms gate's open list`() {
        assertTrue(TermsGate.isOpen(HttpMethod.Get, "/v1/r/AAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(TermsGate.isOpen(HttpMethod.Post, "/v1/r/AAAAAAAAAAAAAAAAAAAAAA"))
        assertFalse(TermsGate.isOpen(HttpMethod.Post, "/v1/sessions/$CURRENT/report-link"))
    }

    // ---------------------------------------------------------------- harness

    private inner class World(
        val store: FakeShopReportStore = FakeShopReportStore(),
        val limit: AddressRateLimit = AddressRateLimit(clock = { now }),
    ) {
        fun mount(builder: ApplicationTestBuilder): HttpClient = with(builder) {
            application {
                module(
                    version = "test",
                    database = null,
                    auth = Auth(ShopAuth(), AccessTokens(KEY)),
                    shopReports = ShopReports(
                        ShopReportService(store = store, renderer = PdfRenderer(), clock = { now }),
                        limit,
                    ),
                )
            }
            createClient { install(ClientContentNegotiation) { json() } }
        }
    }

    private suspend fun HttpClient.issue(userId: String, sessionId: String): ReportLinkResponse {
        val response: HttpResponse = post("/v1/sessions/$sessionId/report-link") { auth(userId) }
        assertEquals(response.bodyAsText(), HttpStatusCode.Created, response.status)
        return Json.decodeFromString(ReportLinkResponse.serializer(), response.bodyAsText())
    }

    private fun io.ktor.client.request.HttpRequestBuilder.auth(userId: String) {
        header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(userId).token}")
    }

    /** The shop account holds a name, a business name and a number; the personal one is a plain Kagua account. */
    private class ShopAuth : AuthStore {
        override suspend fun register(registration: Registration) = RegisterOutcome.InviteUnusable
        override suspend fun findUser(userId: String) = when (userId) {
            SHOP -> UserRow(
                userId, SHOP_OWNER, "business", SHOP_BUSINESS,
                disabled = false, audience = Audience.BUYER, shopMode = true,
            )
            else -> UserRow(userId, "Achieng", "individual", null, disabled = false, audience = Audience.BUYER)
        }
        override suspend fun issueRefresh(
            userId: String, token: String, expiresAt: Instant, userAgent: String?, replaces: String?,
        ) = "r"
        override suspend fun findRefresh(token: String): StoredRefresh? = null
        override suspend fun revokeChain(userId: String) = 0
        override suspend fun credentialsForPhone(phone: String): List<Credentials> = emptyList()
        override suspend fun recordFailedSignIn(userId: String, lockFor: Duration, threshold: Int) = 0
        override suspend fun clearFailedSignIns(userId: String) = Unit
        override suspend fun passwordHashFor(userId: String): String? = null
        override suspend fun setPasswordHash(userId: String, passwordHash: String) = Unit
        override suspend fun markAccountDeleted(userId: String) = Unit
    }

    companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"
        const val SHOP = "50000000-0000-4000-8000-000000000001"
        const val PERSONAL = "50000000-0000-4000-8000-000000000002"
        /** The customer at the tablet now. */
        const val CURRENT = "50000000-0000-4000-8000-0000000000c1"
        /** The customer before. */
        const val EARLIER = "50000000-0000-4000-8000-0000000000c0"
        /** Another account's assessment. */
        const val OTHERS = "50000000-0000-4000-8000-0000000000d1"
        const val SHOP_OWNER = "Mama Njeri"
        const val SHOP_BUSINESS = "Njeri Furniture Mart"
        const val SHOP_PHONE = "+254712345678"
        const val MINUTE = 60_000L

        /** 12 November 2026, 10:00 in Nairobi. */
        const val DAY_ONE = ReportFixtures.DAY_ONE

        /** A Kagua verdict as the buyer prompt writes it. */
        fun verdict(language: String = "en", headline: String = "Solid frame, one loose leg"): String =
            "Here is what I found.\n```qv-verdict\n" +
                """{"verdict":"fair","language":"$language","headline":"$headline",""" +
                """"summary":"A good table once the leg is fixed.",""" +
                """"defects":[{"title":"Loose back leg","area":"structural","severity":"moderate",""" +
                """"what_i_see":"The back left leg moves when pushed.","what_it_means":"It will wobble and get worse.",""" +
                """"what_to_do":"Re-glue the joint and clamp it."}],""" +
                """"unverified":["Underside of the top"],"questions":[]}""" +
                "\n```"
    }
}

/**
 * V40 in memory. [claimDownload] applies the WHERE of the real UPDATE: not expired at the
 * time given, and fewer downloads than the maximum. [sessionInProgress] is V39's answer for
 * the shop account: the assessment [ShopReportTest.CURRENT].
 */
class FakeShopReportStore(
    val inProgress: Map<String, String> = mapOf(ShopReportTest.SHOP to ShopReportTest.CURRENT),
) : ShopReportStore {
    val sessions: MutableMap<String, KaguaReportSession> = listOf(
        ShopReportTest.EARLIER, ShopReportTest.CURRENT, ShopReportTest.OTHERS,
    ).associateWith { id ->
        KaguaReportSession(
            id = id,
            itemTypeId = "wooden-table",
            createdAt = ShopReportTest.DAY_ONE,
            verdictLanguage = "en",
            replies = listOf("Take a photo of the whole table.", ShopReportTest.verdict()),
        )
    }.toMutableMap()
    val links = mutableListOf<ShopReportLinkRow>()

    override suspend fun sessionInProgress(userId: String): String? = inProgress[userId]
    override suspend fun session(sessionId: String): KaguaReportSession? = sessions[sessionId]
    override suspend fun linkCount(sessionId: String): Int = links.count { it.sessionId == sessionId }
    override suspend fun createLink(link: ShopReportLinkRow) {
        check(links.none { it.tokenSha256 == link.tokenSha256 }) { "unique index" }
        links += link
    }
    override suspend fun link(tokenSha256: String): ShopReportLinkRow? = links.firstOrNull { it.tokenSha256 == tokenSha256 }
    override suspend fun claimDownload(tokenSha256: String, at: Long, maxDownloads: Int): Boolean {
        val index = links.indexOfFirst { it.tokenSha256 == tokenSha256 && it.expiresAt > at && it.downloads < maxDownloads }
        if (index < 0) return false
        links[index] = links[index].copy(downloads = links[index].downloads + 1)
        return true
    }
}
