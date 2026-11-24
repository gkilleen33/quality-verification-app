package com.qualityverifier.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qualityverifier.domain.AppId
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.domain.MobileMoneyNetwork
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.domain.VersionPolicy
import com.qualityverifier.server.TermsWorld.Companion.appVersion
import com.qualityverifier.server.TermsWorld.Companion.signedInAs
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.mpesa.CounterpartyCoder
import com.qualityverifier.server.terms.ConsentAction
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.server.terms.TermsGate
import com.qualityverifier.server.terms.TermsTexts
import com.qualityverifier.server.tracker.TrackerAnnotation
import com.qualityverifier.server.tracker.TrackerAnnotationOutcome
import com.qualityverifier.server.tracker.TrackerDeletion
import com.qualityverifier.server.tracker.TrackerStore
import com.qualityverifier.server.tracker.TrackerTransactionRow
import com.qualityverifier.server.tracker.TrackerUnreadDay
import com.qualityverifier.text.MpesaConsentText
import com.qualityverifier.text.TrackerConsentText
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * An in-memory set of V33's two data tables and its deletion record, with V33's rules where
 * the routes rely on them, and PostgresTrackerStore's check of the newest `tracker_send` row
 * against [consents].
 */
class FakeTrackerStore : TrackerStore {
    data class Key(val userId: String, val network: MobileMoneyNetwork, val code: String)

    val transactions = linkedMapOf<Key, TrackerTransactionRow>()
    val annotations = mutableMapOf<Key, TrackerAnnotation>()
    val unread = mutableMapOf<Triple<String, MobileMoneyNetwork, LocalDate>, TrackerUnreadDay>()
    val deletions = mutableListOf<Pair<String, TrackerDeletion>>()
    var consents: FakeConsentStore? = null

    /** Thrown by the next insert, to stand in for a database error. */
    var failNextInsert: Exception? = null

    private suspend fun sendingAgreed(userId: String): Boolean =
        consents?.current(userId)?.get(ConsentKind.TRACKER_SEND)?.action == ConsentAction.AGREED

    override suspend fun insertTransactions(userId: String, rows: List<TrackerTransactionRow>): Int {
        failNextInsert?.let { failNextInsert = null; throw it }
        if (!sendingAgreed(userId)) return 0
        return rows.count { transactions.putIfAbsent(Key(userId, it.network, it.code), it) == null }
    }

    override suspend fun annotate(
        userId: String,
        network: MobileMoneyNetwork,
        code: String,
        annotation: TrackerAnnotation,
    ): TrackerAnnotationOutcome {
        val key = Key(userId, network, code)
        val row = transactions[key] ?: return TrackerAnnotationOutcome.NO_SUCH_TRANSACTION
        if (annotation.category?.allowedFor(row.direction) == false) return TrackerAnnotationOutcome.CATEGORY_DOES_NOT_FIT
        annotations[key] = annotation
        return TrackerAnnotationOutcome.SAVED
    }

    override suspend fun saveUnread(userId: String, days: List<TrackerUnreadDay>) {
        if (!sendingAgreed(userId)) return
        days.forEach { unread[Triple(userId, it.network, it.day)] = it }
    }

    override suspend fun deleteAll(userId: String, audience: Audience, appVersion: Int): TrackerDeletion {
        val withdrawn = consents?.withdraw(userId, audience, ConsentKind.TRACKER_SEND, appVersion) == true
        val gone = transactions.keys.filter { it.userId == userId }
        val days = unread.keys.filter { it.first == userId }
        gone.forEach { transactions.remove(it); annotations.remove(it) }
        days.forEach { unread.remove(it) }
        return TrackerDeletion(gone.size, days.size, withdrawn).also { deletions += userId to it }
    }
}

/**
 * PesaLog's routes: who may use them, under whose terms, what a re-upload does, what the
 * server keeps of a counterparty, and deletion on request.
 */
class TrackerRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store = FakeTrackerStore()
    private val mpesaStore = FakeMpesaStore()
    private val coder = CounterpartyCoder("k".repeat(44))

    private fun TermsWorld.mountTracker(
        builder: ApplicationTestBuilder,
        withCoder: Boolean = true,
        appVersions: AppVersions = AppVersions.UNCONFIGURED,
    ) = builder.mount(
        folder.newFolder(),
        mpesa = Mpesa(mpesaStore, coder),
        tracker = Tracker(store.also { it.consents = consents }, if (withCoder) coder else null),
        appVersions = appVersions,
    )

    private suspend fun HttpClient.deleteRecords(world: TermsWorld): HttpResponse =
        delete("/v1/tracker/records") {
            signedInAs(world.fundi)
            appVersion(1)
        }

    private suspend fun HttpClient.agreeToTrackerTerms(world: TermsWorld, user: UserRow = world.fundi, sha: String? = null) =
        post("/v1/me/consents") {
            signedInAs(user)
            appVersion(1)
            contentType(ContentType.Application.Json)
            setBody(
                """{"version":"${TrackerConsentText.TERMS_VERSION}","language":"en",""" +
                    """"text_sha256":"${sha ?: world.trackerSha}","kinds":["tracker_terms","tracker_transfer"]}""",
            )
        }

    private suspend fun HttpClient.agreeTo(world: TermsWorld, kind: String, user: UserRow = world.fundi) =
        post("/v1/me/consents") {
            signedInAs(user)
            appVersion(1)
            contentType(ContentType.Application.Json)
            val text = TrackerConsentText.textFor(kind) ?: MpesaConsentText.textFor(kind) ?: ""
            val version = if (kind.startsWith("tracker")) TrackerConsentText.VERSION else MpesaConsentText.VERSION
            setBody(
                """{"version":"$version","language":"en",""" +
                    """"text_sha256":"${TrackerConsentText.sha256Of(text)}","kinds":["$kind"]}""",
            )
        }

    /** Terms, reading and sending: everything an upload needs. */
    private suspend fun HttpClient.agreeToAll(world: TermsWorld) {
        assertEquals(HttpStatusCode.NoContent, agreeToTrackerTerms(world).status)
        assertEquals(HttpStatusCode.NoContent, agreeTo(world, "tracker_read").status)
        assertEquals(HttpStatusCode.NoContent, agreeTo(world, "tracker_send").status)
    }

    private suspend fun HttpClient.withdraw(world: TermsWorld, kind: String): HttpResponse =
        post("/v1/me/consents/withdrawal") {
            signedInAs(world.fundi)
            appVersion(1)
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"$kind"}""")
        }

    private fun transaction(
        network: String = "mpesa",
        code: String = "SKA1B2C3D4",
        direction: String = "out",
        type: String = "send",
        amount: String = "1500.0",
        phone: String? = "0712345678",
        name: String? = null,
    ): String = buildString {
        append("""{"network":"$network","code":"$code","direction":"$direction","type":"$type","amount_kes":$amount,""")
        append(""""fee_kes":23.0,"balance_after_kes":4200.5,"occurred_at":1762934400000,""")
        if (phone != null) append(""""counterparty_phone":"$phone",""")
        if (name != null) append(""""counterparty_name":"$name",""")
        append(""""parser_version":2}""")
    }

    private suspend fun HttpClient.upload(world: TermsWorld, vararg rows: String, user: UserRow = world.fundi): HttpResponse =
        post("/v1/tracker/transactions") {
            signedInAs(user)
            appVersion(1)
            header("X-App-Id", "tracker")
            contentType(ContentType.Application.Json)
            setBody("""{"transactions":[${rows.joinToString(",")}]}""")
        }

    private suspend fun HttpClient.annotate(world: TermsWorld, network: String, code: String, body: String): HttpResponse =
        put("/v1/tracker/transactions/$network/$code") {
            signedInAs(world.fundi)
            appVersion(1)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun HttpClient.status(world: TermsWorld, user: UserRow = world.fundi) =
        get("/v1/tracker/status") { signedInAs(user) }

    // ------------------------------------------------------------ who may upload

    @Test
    fun `an upload without an agreement to send is refused and reaches no store`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToTrackerTerms(world)
        client.agreeTo(world, "tracker_read")

        val response = client.upload(world, transaction())

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("tracker_send_required"))
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `an upload without a current agreement to PesaLog's terms is refused`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        // The newest transfer row withdrawn: the terms screen's two boxes no longer both stand.
        assertEquals(HttpStatusCode.NoContent, client.withdraw(world, "tracker_transfer").status)

        val response = client.upload(world, transaction())

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("tracker_terms_required"))
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `the questions cannot be agreed before PesaLog's terms`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)

        val response = client.agreeTo(world, "tracker_read")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("tracker_terms_required"))
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `sending cannot be agreed before reading`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToTrackerTerms(world)

        val response = client.agreeTo(world, "tracker_send")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("tracker_read_required"))
    }

    @Test
    fun `an agreement to a text PesaLog never published is a conflict`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)

        assertEquals(HttpStatusCode.Conflict, client.agreeToTrackerTerms(world, sha = world.englishSha).status)
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `PesaLog's terms are recorded under their own kinds, with their hash and the account's audience`() =
        testApplication {
            val world = TermsWorld()
            val client = world.mountTracker(this)

            client.agreeToTrackerTerms(world)

            assertEquals(
                listOf(ConsentKind.TRACKER_TERMS, ConsentKind.TRACKER_TRANSFER),
                world.consents.rows.map { it.kind },
            )
            world.consents.rows.forEach {
                assertEquals(world.trackerSha, it.agreement?.textSha256)
                assertEquals(Audience.FUNDI, it.agreement?.audience)
            }
        }

    @Test
    fun `a PesaLog route is allowed for an account that agreed to PesaLog's terms but not Fundi Bora's, gate on`() =
        testApplication {
            val world = TermsWorld()
            world.switchGate(Audience.FUNDI, on = true)
            val client = world.mountTracker(this)
            client.agreeToAll(world)

            // Fundi Bora's own routes refuse this account: it never agreed to Fundi Bora's terms.
            val fundiRoute = client.post("/v1/fundi/mpesa/transactions") {
                signedInAs(world.fundi)
                appVersion(1)
                contentType(ContentType.Application.Json)
                setBody("""{"transactions":[]}""")
            }
            assertEquals(HttpStatusCode.Forbidden, fundiRoute.status)
            assertTrue(fundiRoute.bodyAsText().contains("terms_required"))

            assertEquals(HttpStatusCode.OK, client.upload(world, transaction()).status)
            assertEquals(1, store.transactions.size)
            assertEquals(HttpStatusCode.OK, client.status(world).status)
        }

    @Test
    fun `agreeing to PesaLog's terms does not satisfy Fundi Bora's gate`() = testApplication {
        val world = TermsWorld()
        world.switchGate(Audience.FUNDI, on = true)
        val client = world.mountTracker(this)
        client.agreeToTrackerTerms(world)

        val me = Json.parseToJsonElement(client.get("/v1/me") { signedInAs(world.fundi) }.bodyAsText()).jsonObject

        assertTrue(me["terms_required"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a Kagua account is refused on every PesaLog route and cannot agree`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)

        assertEquals(HttpStatusCode.Forbidden, client.upload(world, transaction(), user = world.buyer).status)
        assertEquals(HttpStatusCode.Forbidden, client.status(world, world.buyer).status)
        assertEquals(
            HttpStatusCode.Forbidden,
            client.delete("/v1/tracker/records") { signedInAs(world.buyer) }.status,
        )
        assertEquals(HttpStatusCode.Forbidden, client.agreeToTrackerTerms(world, user = world.buyer).status)
        assertTrue(world.consents.rows.isEmpty())
        assertTrue(store.transactions.isEmpty())
    }

    // ------------------------------------------------------------ what is stored

    @Test
    fun `a re-upload changes nothing and is accepted again`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        val first = Json.parseToJsonElement(client.upload(world, transaction()).bodyAsText()).jsonObject
        client.annotate(world, "mpesa", "SKA1B2C3D4", """{"category":"wages"}""")
        val second = Json.parseToJsonElement(client.upload(world, transaction(amount = "9.0")).bodyAsText()).jsonObject

        assertEquals(1, first["inserted"]!!.jsonPrimitive.int)
        assertEquals(0, second["inserted"]!!.jsonPrimitive.int)
        val accepted = second["accepted"]!!.jsonArray.single().jsonObject
        assertEquals("mpesa", accepted["network"]!!.jsonPrimitive.content)
        assertEquals("SKA1B2C3D4", accepted["code"]!!.jsonPrimitive.content)
        assertEquals(1, store.transactions.size)
        assertEquals("1500.00", store.transactions.values.single().amountKes.toPlainString())
        assertEquals(MpesaCategory.WAGES, store.annotations.values.single().category)
    }

    @Test
    fun `one code on two networks is two payments, and each network's code shape is its own`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        val response = Json.parseToJsonElement(
            client.upload(
                world,
                transaction(network = "mpesa", code = "SKA1B2C3D4"),
                transaction(network = "airtel", code = "SKA1B2C3D4", direction = "in", type = "receive"),
                transaction(network = "airtel", code = "pp260909.1015.a12345"),
                transaction(network = "mpesa", code = "PP260909.1015"),
                transaction(network = "tkash", code = "SKA1B2C3D5"),
            ).bodyAsText(),
        ).jsonObject

        assertEquals(3, response["inserted"]!!.jsonPrimitive.int)
        assertEquals(2, response["rejected"]!!.jsonArray.size)
        assertEquals(
            setOf(
                FakeTrackerStore.Key(world.fundi.id, MobileMoneyNetwork.MPESA, "SKA1B2C3D4"),
                FakeTrackerStore.Key(world.fundi.id, MobileMoneyNetwork.AIRTEL, "SKA1B2C3D4"),
                // Upper-cased, as the CHECK requires.
                FakeTrackerStore.Key(world.fundi.id, MobileMoneyNetwork.AIRTEL, "PP260909.1015.A12345"),
            ),
            store.transactions.keys,
        )
    }

    @Test
    fun `the server keeps a code for the counterparty, never the number or name`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        client.upload(
            world,
            transaction(code = "SKA1B2C3D4", phone = "0712345678"),
            transaction(code = "SKA1B2C3D5", phone = null, name = "JANE DOE HARDWARE"),
        )

        val rows = store.transactions.values.toList()
        assertEquals(CounterpartyBasis.PHONE, rows[0].counterparty?.basis)
        assertEquals(CounterpartyBasis.NAME, rows[1].counterparty?.basis)
        rows.forEach { row ->
            val stored = row.toString()
            listOf("712345678", "JANE", "jane").forEach { assertFalse("$it in $stored", stored.contains(it)) }
        }
    }

    /**
     * The second link between the two datasets (V33): a number or name paid through Fundi
     * Bora's M-Pesa reader and through PesaLog gets one code in both tables.
     */
    @Test
    fun `a counterparty has the same code in Fundi Bora's M-Pesa rows and in PesaLog's`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        assertEquals(HttpStatusCode.NoContent, client.agreeTo(world, "mpesa_read").status)
        assertEquals(HttpStatusCode.NoContent, client.agreeTo(world, "mpesa_send").status)
        suspend fun mpesaUpload(vararg rows: String) = client.post("/v1/fundi/mpesa/transactions") {
            signedInAs(world.fundi)
            appVersion(6)
            contentType(ContentType.Application.Json)
            setBody("""{"transactions":[${rows.joinToString(",")}]}""")
        }
        fun mpesaRow(code: String, phone: String?, name: String?) = transaction(code = code, phone = phone, name = name)
            .replace(""""network":"mpesa",""", "")

        assertEquals(
            HttpStatusCode.OK,
            mpesaUpload(mpesaRow("SKA1B2C3D4", "0712345678", null), mpesaRow("SKA1B2C3D5", null, "Jane Doe  Hardware")).status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.upload(
                world,
                transaction(code = "SKA1B2C3E1", phone = "+254712345678"),
                transaction(network = "airtel", code = "P3RAIQM1E4N", phone = null, name = "JANE DOE HARDWARE"),
            ).status,
        )

        val fundiCodes = mpesaStore.transactions.values.map { it.counterparty }
        val trackerCodes = store.transactions.values.map { it.counterparty }
        assertEquals(fundiCodes, trackerCodes)
        assertTrue(trackerCodes.all { it != null })
    }

    @Test
    fun `a note is saved, and one that does not fit is refused`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        client.upload(world, transaction(), transaction(network = "airtel", code = "P3RAIQM1E4N"))

        assertEquals(
            HttpStatusCode.NoContent,
            client.annotate(world, "mpesa", "SKA1B2C3D4", """{"category":"material_purchases","comment":" Timber "}""").status,
        )
        val saved = store.annotations.values.single()
        assertEquals(MpesaCategory.MATERIAL_PURCHASES, saved.category)
        assertEquals("Timber", saved.comment)

        assertEquals(HttpStatusCode.NoContent, client.annotate(world, "airtel", "P3RAIQM1E4N", """{"category":"wages"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.annotate(world, "mpesa", "SKA1B2C3D4", """{"category":"customer_payment"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.annotate(world, "mpesa", "SKA1B2C3D4", """{"category":"rent"}""").status)
        // A piece is Fundi Bora's: the strict reader refuses the field.
        assertEquals(HttpStatusCode.BadRequest, client.annotate(world, "mpesa", "SKA1B2C3D4", """{"piece_id":"x"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.annotate(world, "airtel", "SKA1B2C3D4", """{"category":"wages"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.annotate(world, "tkash", "SKA1B2C3D4", """{"category":"wages"}""").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.annotate(world, "mpesa", "SKA1B2C3D4", """{"comment":"${"x".repeat(501)}"}""").status,
        )
    }

    @Test
    fun `unread counts replace the network's day, and an unknown network is refused`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        suspend fun send(body: String) = client.put("/v1/tracker/unread") {
            signedInAs(world.fundi)
            appVersion(1)
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        fun row(network: String, count: Int) = """{"network":"$network","day":"2026-11-12","unread":$count,"parser_version":2}"""

        assertEquals(HttpStatusCode.NoContent, send("""{"days":[${row("mpesa", 2)},${row("airtel", 1)}]}""").status)
        assertEquals(HttpStatusCode.NoContent, send("""{"days":[${row("mpesa", 3)}]}""").status)

        assertEquals(2, store.unread.size)
        assertEquals(3, store.unread.values.first { it.network == MobileMoneyNetwork.MPESA }.unread)
        assertEquals(HttpStatusCode.BadRequest, send("""{"days":[${row("mpesa", -1)}]}""").status)
        assertEquals(HttpStatusCode.BadRequest, send("""{"days":[${row("tkash", 1)}]}""").status)
    }

    // ------------------------------------------------------------ withdrawal and deletion

    @Test
    fun `withdrawing reading withdraws sending too, and stops uploads`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        assertEquals(HttpStatusCode.NoContent, client.withdraw(world, "tracker_read").status)

        val newest = world.consents.rows.groupBy { it.kind }.mapValues { it.value.last().action }
        assertEquals(ConsentAction.WITHDRAWN, newest[ConsentKind.TRACKER_READ])
        assertEquals(ConsentAction.WITHDRAWN, newest[ConsentKind.TRACKER_SEND])
        assertEquals(HttpStatusCode.Forbidden, client.upload(world, transaction()).status)
    }

    @Test
    fun `PesaLog's terms themselves cannot be withdrawn`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToTrackerTerms(world)

        assertEquals(HttpStatusCode.BadRequest, client.withdraw(world, "tracker_terms").status)
    }

    @Test
    fun `deletion removes the account's rows, keeps its consents, and works after withdrawing`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        client.upload(world, transaction(), transaction(network = "airtel", code = "P3RAIQM1E4N"))
        client.put("/v1/tracker/unread") {
            signedInAs(world.fundi)
            appVersion(1)
            contentType(ContentType.Application.Json)
            setBody("""{"days":[{"network":"mpesa","day":"2026-11-12","unread":2,"parser_version":2}]}""")
        }
        // Another account's rows must survive.
        store.transactions[FakeTrackerStore.Key("someone-else", MobileMoneyNetwork.MPESA, "SKA1B2C3D9")] =
            store.transactions.values.first()
        client.withdraw(world, "tracker_read")
        val consentsBefore = world.consents.rows.toList()

        val response = client.deleteRecords(world)

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["transactions_deleted"]!!.jsonPrimitive.int)
        assertEquals(1, body["unread_days_deleted"]!!.jsonPrimitive.int)
        assertFalse(body["sending_withdrawn"]!!.jsonPrimitive.boolean)
        assertTrue(store.transactions.keys.none { it.userId == world.fundi.id })
        assertEquals(1, store.transactions.size)
        assertTrue(store.unread.isEmpty())
        assertEquals(listOf(world.fundi.id to TrackerDeletion(2, 1, sendingWithdrawn = false)), store.deletions)
        // Sending was already withdrawn with reading, so nothing more is written.
        assertEquals(consentsBefore, world.consents.rows)
    }

    @Test
    fun `deletion with sending on withdraws it, so the next upload cannot refill the server`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        client.upload(world, transaction())
        val consentsBefore = world.consents.rows.toList()

        val body = Json.parseToJsonElement(client.deleteRecords(world).bodyAsText()).jsonObject

        assertTrue(body["sending_withdrawn"]!!.jsonPrimitive.boolean)
        // Every earlier row stands; the withdrawal is one new row after them.
        assertEquals(consentsBefore, world.consents.rows.dropLast(1))
        val added = world.consents.rows.last()
        assertEquals(ConsentKind.TRACKER_SEND to ConsentAction.WITHDRAWN, added.kind to added.action)
        assertEquals(HttpStatusCode.Forbidden, client.upload(world, transaction(code = "SKA1B2C3D5")).status)
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `an upload the route let through finds sending withdrawn by a deletion and stores nothing`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        // The race the store's lock closes: the deletion's withdrawal lands between the route's
        // check and the insert. Simulated by withdrawing behind the gate's cache, which the
        // status call fills with "agreed".
        assertEquals(HttpStatusCode.OK, client.status(world).status)
        world.consents.withdraw(world.fundi.id, Audience.FUNDI, ConsentKind.TRACKER_SEND, 1)

        val response = Json.parseToJsonElement(client.upload(world, transaction()).bodyAsText()).jsonObject

        assertEquals(0, response["inserted"]!!.jsonPrimitive.int)
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `deletion needs the app version, for the withdrawal row`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)

        assertEquals(HttpStatusCode.BadRequest, client.delete("/v1/tracker/records") { signedInAs(world.fundi) }.status)
        assertTrue(store.deletions.isEmpty())
    }

    // ------------------------------------------------------------ status, mounting, logging

    @Test
    fun `status reports PesaLog's terms and both answers, and is open before agreeing`() = testApplication {
        val world = TermsWorld()
        world.switchGate(Audience.FUNDI, on = true)
        val client = world.mountTracker(this)
        suspend fun status() = Json.parseToJsonElement(client.status(world).bodyAsText()).jsonObject

        val before = status()
        assertTrue(before["terms_required"]!!.jsonPrimitive.boolean)
        val terms = before["terms"]!!.jsonObject
        assertEquals(TrackerConsentText.TERMS_VERSION, terms["version"]!!.jsonPrimitive.content)
        val english = terms["languages"]!!.jsonArray.single().jsonObject
        assertEquals(world.trackerSha, english["sha256"]!!.jsonPrimitive.content)
        assertEquals("v1/terms/tracker/${TrackerConsentText.TERMS_VERSION}/en", english["path"]!!.jsonPrimitive.content)

        client.agreeToAll(world)
        val after = status()
        assertFalse(after["terms_required"]!!.jsonPrimitive.boolean)
        assertTrue(after["transfer_agreed"]!!.jsonPrimitive.boolean)
        assertTrue(after["read_agreed"]!!.jsonPrimitive.boolean)
        assertTrue(after["send_agreed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `PesaLog's text is served at the path status names`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)

        val response = client.get("/v1/terms/tracker/${TrackerConsentText.TERMS_VERSION}/en")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(String(world.trackerBytes), response.bodyAsText())
    }

    @Test
    fun `without the HMAC key the data routes are not mounted, and status and deletion still are`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this, withCoder = false)
        client.agreeToAll(world)

        assertEquals(HttpStatusCode.NotFound, client.upload(world, transaction()).status)
        assertEquals(HttpStatusCode.OK, client.status(world).status)
        assertEquals(HttpStatusCode.OK, client.deleteRecords(world).status)
    }

    @Test
    fun `without PesaLog's text the gate refuses, and the health check says so`() = runBlocking {
        val world = TermsWorld()
        // A server whose jar lacks PesaLog's text: fails closed, unlike Fundi Bora's gate.
        val bare = TermsGate(world.settings, world.consents, TermsTexts.NONE)

        val status = bare.trackerStatusFor(world.fundi.id)

        assertTrue(status.required)
        assertTrue(status.refuses)
        assertTrue(bare.missingTexts().contains("tracker ${TrackerConsentText.TERMS_VERSION}"))
    }

    @Test
    fun `an escaped path never borrows PesaLog's exemption from Fundi Bora's gate`() {
        assertTrue(TermsGate.isTracker("/v1/tracker/transactions/airtel/PP260909.1015.A12345"))
        assertFalse(TermsGate.isTracker("/v1/tracker/../fundi/pieces"))
        assertFalse(TermsGate.isTracker("/v1/tracker/%2E%2E/fundi/pieces"))
        assertFalse(TermsGate.isTracker("/v1/fundi/pieces"))
    }

    @Test
    fun `a database failure on a PesaLog route is logged without its message`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        store.failNextInsert = java.sql.SQLException("Failing row contains (SKA1B2C3D4, 1500.00, Kamau 0712345678)", "23514")
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        try {
            assertEquals(HttpStatusCode.InternalServerError, client.upload(world, transaction()).status)
        } finally {
            root.detachAppender(events)
        }
        val logged = events.list.joinToString("\n") {
            it.formattedMessage + (it.throwableProxy?.message ?: "") + (it.throwableProxy?.cause?.message ?: "")
        }
        assertTrue("expected the failure to be logged:\n$logged", logged.contains("23514"))
        listOf("Kamau", "0712345678", "1500.00").forEach { assertFalse("$it reached the log:\n$logged", logged.contains(it)) }
    }

    @Test
    fun `an escaped PesaLog path is still logged as one`() {
        assertTrue(carriesMoneyRecords("/v1/%74racker/transactions"))
        assertTrue(carriesMoneyRecords("/v1/fundi/mpesa/transactions"))
        assertFalse(carriesMoneyRecords("/v1/fundi/pieces"))
    }

    @Test
    fun `an unreadable upload is logged without its body`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        try {
            val response = client.upload(world, transaction().removeSuffix("}") + ""","message_text":"0799111222 JANE"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            root.detachAppender(events)
        }
        val logged = events.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertTrue("expected the refusal to be logged", logged.contains("/v1/tracker/transactions"))
        listOf("0712345678", "0799111222", "JANE").forEach { assertFalse("$it reached the log:\n$logged", logged.contains(it)) }
        assertTrue(store.transactions.isEmpty())
    }

    // ------------------------------------------------------------ row 32: three codes (V42)

    /** A row as PesaLog 0.2.0 sends it: the name with the number, or the name with the masked number. */
    private fun transaction02(
        code: String,
        direction: String,
        type: String,
        name: String?,
        phone: String? = null,
        masked: String? = null,
        network: String = "mpesa",
    ): String = buildString {
        append("""{"network":"$network","code":"$code","direction":"$direction","type":"$type","amount_kes":500.0,""")
        append(""""occurred_at":1762934400000,""")
        if (phone != null) append(""""counterparty_phone":"$phone",""")
        if (name != null) append(""""counterparty_name":"$name",""")
        if (masked != null) append(""""counterparty_masked":"$masked",""")
        append(""""parser_version":2}""")
    }

    private fun stored(code: String) = store.transactions.values.single { it.code == code }

    @Test
    fun `row 32 - a send and a receipt from the same person get equal name and masked-number codes`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        // "JANE DOE 0720456123" on money sent, "jane  doe 0720***123" on money received: the
        // names differ only in case and spaces.
        val response = client.upload(
            world,
            transaction02("SFA1B2C3D5", "out", "send", name = "JANE DOE", phone = "254720456123"),
            transaction02("UINRL7DIVX", "in", "receive", name = "jane  doe", masked = "0720***123"),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val send = stored("SFA1B2C3D5")
        val receipt = stored("UINRL7DIVX")
        assertTrue(send.counterpartyNameCode != null && send.counterpartyMaskedCode != null)
        assertEquals(send.counterpartyNameCode, receipt.counterpartyNameCode)
        assertEquals(send.counterpartyMaskedCode, receipt.counterpartyMaskedCode)
        // The code each has always had is unchanged: the number for the send, the name for the receipt.
        assertEquals(coder.code("0720456123", null), send.counterparty)
        assertEquals(coder.code(null, "JANE DOE"), receipt.counterparty)
    }

    @Test
    fun `row 32 - a different name with the same masked number gets a different name code`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        client.upload(
            world,
            transaction02("UINRL7DIVX", "in", "receive", name = "JANE DOE", masked = "0720***123"),
            transaction02("UINRL7DIVY", "in", "receive", name = "JOHN DOE", masked = "0720***123"),
        )

        assertEquals(stored("UINRL7DIVX").counterpartyMaskedCode, stored("UINRL7DIVY").counterpartyMaskedCode)
        assertTrue(stored("UINRL7DIVX").counterpartyNameCode != stored("UINRL7DIVY").counterpartyNameCode)
    }

    /**
     * Every path a payment's name or number could reach the server's records: the stored rows,
     * the upload's log lines, a refused row, a masked value of the wrong shape, and a database
     * error carrying the row back in its message.
     */
    @Test
    fun `row 32 - no plain name or number appears in any stored row or log line`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        try {
            client.upload(
                world,
                // Names unlike TermsWorld's account names, which a log line may carry legitimately.
                transaction02("SFA1B2C3D5", "out", "send", name = "MUTHONI NJERI", phone = "254720456123"),
                transaction02("UINRL7DIVX", "in", "receive", name = "Muthoni  Njeri", masked = "0720***123"),
                // Masked value of the wrong shape: stored with no masked-number code, never echoed.
                transaction02("UINRL7DIVY", "in", "receive", name = "CHEPKOECH ROTICH", masked = "0799 111 222 ROTICH"),
                // Refused (unknown network), with a name and number in it.
                transaction02("UINRL7DIVZ", "in", "receive", name = "AKINYI ATIENO", masked = "0711***999", network = "tkash"),
            )
            store.failNextInsert = java.sql.SQLException("Failing row contains (MUTHONI NJERI, 0720***123)", "23514")
            client.upload(world, transaction02("SFA1B2C3E1", "out", "send", name = "MUTHONI NJERI", phone = "254720456123"))
        } finally {
            root.detachAppender(events)
        }

        assertEquals(3, store.transactions.size)
        assertEquals(null, stored("UINRL7DIVY").counterpartyMaskedCode)
        val logged = events.list.joinToString("\n") {
            it.formattedMessage + (it.throwableProxy?.message ?: "") + (it.throwableProxy?.cause?.message ?: "")
        }
        assertTrue("expected the uploads to be logged:\n$logged", logged.contains("PesaLog upload for"))
        val plain = listOf(
            "MUTHONI", "muthoni", "Muthoni", "NJERI", "njeri", "CHEPKOECH", "chepkoech", "ROTICH", "rotich",
            "AKINYI", "akinyi",
            "720456123", "0720", "***", "456123", "0799", "111 222", "0711",
        )
        val rows = store.transactions.values.joinToString("\n") { it.toString() }
        plain.forEach {
            assertFalse("'$it' is in a stored row:\n$rows", rows.contains(it))
            assertFalse("'$it' reached the log:\n$logged", logged.contains(it))
        }
    }

    @Test
    fun `row 32 - a PesaLog 0_1_0 upload without the masked field still succeeds`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        // As 0.1.0 sends them: the number and no name on money sent, the name and no masked
        // number on money received.
        val response = client.upload(
            world,
            transaction(code = "SFA1B2C3D5", phone = "254720456123", name = null),
            transaction(code = "UINRL7DIVX", direction = "in", type = "receive", phone = null, name = "JANE DOE"),
        )

        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(2, body["inserted"]!!.jsonPrimitive.int)
        // The codes the server can make from what it has.
        val send = stored("SFA1B2C3D5")
        assertEquals(CounterpartyBasis.PHONE, send.counterparty?.basis)
        assertEquals(null, send.counterpartyNameCode)
        assertTrue(send.counterpartyMaskedCode != null)
        val receipt = stored("UINRL7DIVX")
        assertEquals(CounterpartyBasis.NAME, receipt.counterparty?.basis)
        assertEquals(receipt.counterparty?.code, receipt.counterpartyNameCode)
        assertEquals(null, receipt.counterpartyMaskedCode)
    }

    @Test
    fun `row 32 - Fundi Bora rows from v0_5_0 phones get the codes the server can make`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        assertEquals(HttpStatusCode.NoContent, client.agreeTo(world, "mpesa_read").status)
        assertEquals(HttpStatusCode.NoContent, client.agreeTo(world, "mpesa_send").status)
        fun mpesaRow(code: String, direction: String, type: String, phone: String?, name: String?) =
            transaction(code = code, direction = direction, type = type, phone = phone, name = name)
                .replace(""""network":"mpesa",""", "")

        val response = client.post("/v1/fundi/mpesa/transactions") {
            signedInAs(world.fundi)
            appVersion(6)
            contentType(ContentType.Application.Json)
            setBody(
                """{"transactions":[${mpesaRow("SFA1B2C3D5", "out", "send", "254720456123", null)},""" +
                    """${mpesaRow("UINRL7DIVX", "in", "receive", null, "JANE DOE")}]}""",
            )
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val send = mpesaStore.transactions.values.single { it.code == "SFA1B2C3D5" }
        val receipt = mpesaStore.transactions.values.single { it.code == "UINRL7DIVX" }
        assertEquals(coder.codes("254720456123", null, null).maskedCode, send.counterpartyMaskedCode)
        assertEquals(null, send.counterpartyNameCode)
        assertEquals(coder.codes(null, "JANE DOE", null).nameCode, receipt.counterpartyNameCode)
        assertEquals(null, receipt.counterpartyMaskedCode)
    }

    // ------------------------------------------------------------ row 33: the consent wording

    private suspend fun HttpClient.agreeToVersion(world: TermsWorld, kind: String, version: String, sha: String) =
        post("/v1/me/consents") {
            signedInAs(world.fundi)
            appVersion(1)
            contentType(ContentType.Application.Json)
            setBody("""{"version":"$version","language":"en","text_sha256":"$sha","kinds":["$kind"]}""")
        }

    @Test
    fun `row 33 - an agreement to the old consent wording still checks`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        assertEquals(HttpStatusCode.NoContent, client.agreeToTrackerTerms(world).status)
        val old = TrackerConsentText.VERSION_2026_11

        // What a PesaLog 0.1.0 phone sends: the 2026-11 label and the hash of the words it shows.
        listOf("tracker_read", "tracker_send").forEach { kind ->
            val sha = TrackerConsentText.published.getValue(Triple(kind, old, "en"))
            assertEquals(kind, HttpStatusCode.NoContent, client.agreeToVersion(world, kind, old, sha).status)
        }

        assertEquals(listOf(old, old), world.consents.rows.takeLast(2).map { it.agreement?.version })
        assertEquals(HttpStatusCode.OK, client.upload(world, transaction()).status)
        assertEquals(1, store.transactions.size)
    }

    @Test
    fun `row 33 - the new wording is recorded under its own label, and a mixed label and hash is refused`() = testApplication {
        val world = TermsWorld()
        val client = world.mountTracker(this)
        client.agreeToAll(world)

        assertEquals(TrackerConsentText.VERSION, world.consents.rows.last().agreement?.version)
        assertEquals(TrackerConsentText.sha256Of(TrackerConsentText.SEND), world.consents.rows.last().agreement?.textSha256)
        // The new wording's hash under the old label is no text any phone showed.
        val newHash = TrackerConsentText.sha256Of(TrackerConsentText.SEND)
        assertEquals(
            HttpStatusCode.Conflict,
            client.agreeToVersion(world, "tracker_send", TrackerConsentText.VERSION_2026_11, newHash).status,
        )
    }

    @Test
    fun `a PesaLog build below its minimum is refused, and Fundi Bora's minimum does not apply to it`() = testApplication {
        val world = TermsWorld()
        val versions = AppVersions(
            mapOf(
                AppId.TRACKER to VersionPolicy(latest = 3, minimum = 2, downloadUrl = VersionPolicy.DEFAULT_DOWNLOAD_URL),
                AppId.FUNDI to VersionPolicy(latest = 9, minimum = 9, downloadUrl = VersionPolicy.DEFAULT_DOWNLOAD_URL),
            ),
        )
        val client = world.mountTracker(this, appVersions = versions)
        suspend fun statusAs(code: Int) = client.get("/v1/tracker/status") {
            signedInAs(world.fundi)
            header("X-App-Id", "tracker")
            appVersion(code)
        }

        assertEquals(UPGRADE_REQUIRED, statusAs(1).status)
        assertEquals(HttpStatusCode.OK, statusAs(2).status)

        val config = Json.parseToJsonElement(client.get("/v1/auth/config?app=tracker").bodyAsText()).jsonObject
        assertEquals(3, config["latest"]!!.jsonPrimitive.int)
        assertEquals(2, config["minimum"]!!.jsonPrimitive.int)
    }
}
