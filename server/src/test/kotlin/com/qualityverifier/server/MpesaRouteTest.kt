package com.qualityverifier.server

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.domain.MpesaCategory
import com.qualityverifier.server.TermsWorld.Companion.appVersion
import com.qualityverifier.server.TermsWorld.Companion.signedInAs
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.mpesa.AnnotationOutcome
import com.qualityverifier.server.mpesa.CounterpartyCoder
import com.qualityverifier.server.mpesa.MpesaAnnotation
import com.qualityverifier.server.mpesa.MpesaStore
import com.qualityverifier.server.mpesa.MpesaTransactionRow
import com.qualityverifier.server.mpesa.UnreadDay
import com.qualityverifier.server.terms.ConsentAction
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.text.MpesaConsentText
import io.ktor.client.HttpClient
import io.ktor.client.request.get
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** An in-memory pair of V29 tables, with V29's rules where the routes rely on them. */
class FakeMpesaStore : MpesaStore {
    val transactions = linkedMapOf<Pair<String, String>, MpesaTransactionRow>()
    val annotations = mutableMapOf<Pair<String, String>, MpesaAnnotation>()
    val unread = mutableMapOf<Pair<String, LocalDate>, UnreadDay>()

    /** (user, piece) pairs that exist on the server. */
    val pieces = mutableSetOf<Pair<String, String>>()

    override suspend fun insertTransactions(userId: String, rows: List<MpesaTransactionRow>): Int =
        rows.count { transactions.putIfAbsent(userId to it.code, it) == null }

    override suspend fun annotate(userId: String, code: String, annotation: MpesaAnnotation): AnnotationOutcome {
        val row = transactions[userId to code] ?: return AnnotationOutcome.NO_SUCH_TRANSACTION
        if (annotation.category?.allowedFor(row.direction) == false) return AnnotationOutcome.CATEGORY_DOES_NOT_FIT
        if (annotation.pieceId != null && (userId to annotation.pieceId) !in pieces) return AnnotationOutcome.NO_SUCH_PIECE
        annotations[userId to code] = annotation
        return AnnotationOutcome.SAVED
    }

    override suspend fun saveUnread(userId: String, days: List<UnreadDay>) {
        days.forEach { unread[userId to it.day] = it }
    }
}

/**
 * Who may upload, what a re-upload does, what the server keeps of a counterparty, and the
 * consents that allow it.
 */
class MpesaRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val store = FakeMpesaStore()
    private val coder = CounterpartyCoder("k".repeat(44))

    private fun TermsWorld.mountMpesa(builder: ApplicationTestBuilder, mounted: Boolean = true) =
        builder.mount(folder.newFolder(), mpesa = if (mounted) Mpesa(store, coder) else null)

    private suspend fun HttpClient.agreeTo(world: TermsWorld, kind: String, user: UserRow = world.fundi, sha: String? = null) =
        post("/v1/me/consents") {
            signedInAs(user)
            appVersion()
            contentType(ContentType.Application.Json)
            val text = MpesaConsentText.textFor(kind) ?: ""
            setBody(
                """{"version":"${MpesaConsentText.VERSION}","language":"en",""" +
                    """"text_sha256":"${sha ?: MpesaConsentText.sha256Of(text)}","kinds":["$kind"]}""",
            )
        }

    private suspend fun HttpClient.agreeToBoth(world: TermsWorld) {
        assertEquals(HttpStatusCode.NoContent, agreeTo(world, "mpesa_read").status)
        assertEquals(HttpStatusCode.NoContent, agreeTo(world, "mpesa_send").status)
    }

    private suspend fun HttpClient.withdraw(world: TermsWorld, kind: String): HttpResponse =
        post("/v1/me/consents/withdrawal") {
            signedInAs(world.fundi)
            appVersion()
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"$kind"}""")
        }

    private fun transaction(
        code: String = "SKA1B2C3D4",
        direction: String = "out",
        type: String = "send",
        amount: String = "1500.0",
        phone: String? = "0712345678",
        name: String? = null,
    ): String = buildString {
        append("""{"code":"$code","direction":"$direction","type":"$type","amount_kes":$amount,""")
        append(""""fee_kes":23.0,"balance_after_kes":4200.5,"occurred_at":1762934400000,""")
        if (phone != null) append(""""counterparty_phone":"$phone",""")
        if (name != null) append(""""counterparty_name":"$name",""")
        append(""""parser_version":2}""")
    }

    private suspend fun HttpClient.upload(
        world: TermsWorld,
        vararg rows: String,
        user: UserRow = world.fundi,
    ): HttpResponse = post("/v1/fundi/mpesa/transactions") {
        signedInAs(user)
        appVersion(6)
        contentType(ContentType.Application.Json)
        setBody("""{"transactions":[${rows.joinToString(",")}]}""")
    }

    private suspend fun HttpClient.annotate(world: TermsWorld, code: String, body: String): HttpResponse =
        put("/v1/fundi/mpesa/transactions/$code") {
            signedInAs(world.fundi)
            appVersion(6)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `an upload without an agreement to send is refused and reaches no store`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        assertEquals(HttpStatusCode.NoContent, client.agreeTo(world, "mpesa_read").status)

        val response = client.upload(world, transaction())

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("mpesa_send_required"))
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `a Kagua account is refused`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)

        val response = client.upload(world, transaction(), user = world.buyer)

        assertEquals(HttpStatusCode.Forbidden, response.status)
        assertTrue(response.bodyAsText().contains("not_a_fundi"))
        assertTrue(store.transactions.isEmpty())
    }

    @Test
    fun `a Kagua account cannot agree to the M-Pesa questions`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)

        assertEquals(HttpStatusCode.Forbidden, client.agreeTo(world, "mpesa_read", user = world.buyer).status)
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `a re-upload changes nothing and is accepted again`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)

        val first = Json.parseToJsonElement(client.upload(world, transaction()).bodyAsText()).jsonObject
        client.annotate(world, "SKA1B2C3D4", """{"category":"wages"}""")
        val second = Json.parseToJsonElement(client.upload(world, transaction(amount = "9.0")).bodyAsText()).jsonObject

        assertEquals(1, first["inserted"]!!.jsonPrimitive.int)
        assertEquals(0, second["inserted"]!!.jsonPrimitive.int)
        assertEquals("SKA1B2C3D4", second["accepted"]!!.jsonArray.single().jsonPrimitive.content)
        assertEquals(1, store.transactions.size)
        // The first upload's row stands, and so does the note made on it.
        assertEquals("1500.00", store.transactions.values.single().amountKes.toPlainString())
        assertEquals(MpesaCategory.WAGES, store.annotations.values.single().category)
    }

    @Test
    fun `the server keeps a code for the counterparty, never the number or name`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)

        client.upload(
            world,
            transaction(code = "SKA1B2C3D4", phone = "0712345678"),
            transaction(code = "SKA1B2C3D5", phone = "+254712345678"),
            transaction(code = "SKA1B2C3D6", phone = null, name = "JANE DOE HARDWARE"),
        )

        val rows = store.transactions.values.toList()
        assertEquals(CounterpartyBasis.PHONE, rows[0].counterparty?.basis)
        assertEquals(rows[0].counterparty, rows[1].counterparty)
        assertEquals(CounterpartyBasis.NAME, rows[2].counterparty?.basis)
        rows.forEach { row ->
            val stored = row.toString()
            listOf("712345678", "JANE", "jane").forEach { assertFalse("$it in $stored", stored.contains(it)) }
        }
    }

    @Test
    fun `a row out of range is refused alone`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)

        val response = Json.parseToJsonElement(
            client.upload(
                world,
                transaction(code = "SKA1B2C3D4"),
                transaction(code = "SKA1B2C3D5", type = "failed"),
                transaction(code = "SKA1B2C3D6", amount = "-5.0"),
                transaction(code = "short"),
            ).bodyAsText(),
        ).jsonObject

        assertEquals(listOf("SKA1B2C3D4"), response["accepted"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(3, response["rejected"]!!.jsonArray.size)
        assertEquals(1, store.transactions.size)
    }

    @Test
    fun `an invalid copy of a code does not shadow a valid one, and a time out of range is refused alone`() =
        testApplication {
            val world = TermsWorld()
            val client = world.mountMpesa(this)
            client.agreeToBoth(world)

            val response = Json.parseToJsonElement(
                client.upload(
                    world,
                    transaction(code = "SKA1B2C3D4", type = "failed"),
                    transaction(code = "SKA1B2C3D4"),
                    transaction(code = "SKA1B2C3D5").replace("1762934400000", "9300000000000000"),
                ).bodyAsText(),
            ).jsonObject

            assertEquals(listOf("SKA1B2C3D4"), response["accepted"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(listOf("SKA1B2C3D5"), response["rejected"]!!.jsonArray.map { it.jsonPrimitive.content })
            assertEquals(1, store.transactions.size)
        }

    @Test
    fun `withdrawing the agreement to send stops uploads`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)
        assertEquals(HttpStatusCode.OK, client.upload(world, transaction()).status)

        assertEquals(HttpStatusCode.NoContent, client.withdraw(world, "mpesa_send").status)

        assertEquals(HttpStatusCode.Forbidden, client.upload(world, transaction(code = "SKA1B2C3D5")).status)
        assertEquals(1, store.transactions.size)
    }

    @Test
    fun `withdrawing the agreement to read withdraws sending too`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)

        assertEquals(HttpStatusCode.NoContent, client.withdraw(world, "mpesa_read").status)

        val newest = world.consents.rows.groupBy { it.kind }.mapValues { it.value.last().action }
        assertEquals(ConsentAction.WITHDRAWN, newest[ConsentKind.MPESA_READ])
        assertEquals(ConsentAction.WITHDRAWN, newest[ConsentKind.MPESA_SEND])
        assertEquals(HttpStatusCode.Forbidden, client.upload(world, transaction()).status)
    }

    @Test
    fun `sending cannot be agreed before reading`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)

        val response = client.agreeTo(world, "mpesa_send")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("mpesa_read_required"))
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `an agreement to a text the app never showed is a conflict`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)

        val response = client.agreeTo(world, "mpesa_read", sha = "b".repeat(64))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("mpesa_text_mismatch"))
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `an M-Pesa agreement writes one row with the question's hash`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)

        client.agreeTo(world, "mpesa_read")

        val row = world.consents.rows.single()
        assertEquals(ConsentKind.MPESA_READ, row.kind)
        assertEquals(MpesaConsentText.sha256Of(MpesaConsentText.READ), row.agreement?.textSha256)
        assertEquals(MpesaConsentText.VERSION, row.agreement?.version)
    }

    @Test
    fun `a terms agreement and an M-Pesa question cannot be sent together`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)

        val response = client.post("/v1/me/consents") {
            signedInAs(world.fundi)
            appVersion()
            contentType(ContentType.Application.Json)
            setBody("""{"version":"2026-11","language":"en","text_sha256":"${world.englishSha}","kinds":["terms","transfer","mpesa_read"]}""")
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `me reports both answers`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        suspend fun me() = Json.parseToJsonElement(client.get("/v1/me") { signedInAs(world.fundi) }.bodyAsText()).jsonObject

        assertFalse(me()["mpesa_read_agreed"]!!.jsonPrimitive.boolean)
        client.agreeTo(world, "mpesa_read")
        assertTrue(me()["mpesa_read_agreed"]!!.jsonPrimitive.boolean)
        assertFalse(me()["mpesa_send_agreed"]!!.jsonPrimitive.boolean)
        client.agreeTo(world, "mpesa_send")
        assertTrue(me()["mpesa_send_agreed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `without the HMAC key the routes are not mounted`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this, mounted = false)
        client.agreeToBoth(world)

        assertEquals(HttpStatusCode.NotFound, client.upload(world, transaction()).status)
    }

    @Test
    fun `a note is saved, and one that does not fit is refused`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)
        client.upload(world, transaction())
        val piece = "00000000-0000-4000-8000-0000000000aa"
        store.pieces += world.fundi.id to piece

        assertEquals(
            HttpStatusCode.NoContent,
            client.annotate(world, "SKA1B2C3D4", """{"category":"material_purchases","piece_id":"$piece","comment":" Timber "}""").status,
        )
        val saved = store.annotations.values.single()
        assertEquals(MpesaCategory.MATERIAL_PURCHASES, saved.category)
        assertEquals("Timber", saved.comment)

        assertEquals(HttpStatusCode.BadRequest, client.annotate(world, "SKA1B2C3D4", """{"category":"customer_payment"}""").status)
        assertEquals(HttpStatusCode.BadRequest, client.annotate(world, "SKA1B2C3D4", """{"category":"rent"}""").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.annotate(world, "SKA1B2C3D4", """{"piece_id":"00000000-0000-4000-8000-0000000000bb"}""").status,
        )
        assertEquals(HttpStatusCode.NotFound, client.annotate(world, "SKZZZZZZZZ", """{"category":"wages"}""").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            client.annotate(world, "SKA1B2C3D4", """{"comment":"${"x".repeat(501)}"}""").status,
        )
    }

    @Test
    fun `unread counts replace the day's count`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)
        suspend fun send(count: Int) = client.put("/v1/fundi/mpesa/unread") {
            signedInAs(world.fundi)
            appVersion(6)
            contentType(ContentType.Application.Json)
            setBody("""{"days":[{"day":"2026-11-12","unread":$count,"parser_version":2}]}""")
        }

        assertEquals(HttpStatusCode.NoContent, send(2).status)
        assertEquals(HttpStatusCode.NoContent, send(3).status)

        assertEquals(3, store.unread.values.single().unread)
        assertEquals(6, store.unread.values.single().appVersion)
        assertEquals(HttpStatusCode.BadRequest, send(-1).status)
    }

    @Test
    fun `an unreadable upload is logged without its body`() = testApplication {
        val world = TermsWorld()
        val client = world.mountMpesa(this)
        client.agreeToBoth(world)
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val events = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(events)
        try {
            // A stray key: strict JSON refuses it, and its message quotes the input.
            val response = client.upload(world, transaction().removeSuffix("}") + ""","sender_number":"0799111222"}""")
            assertEquals(HttpStatusCode.BadRequest, response.status)
        } finally {
            root.detachAppender(events)
        }
        val logged = events.list.joinToString("\n") { it.formattedMessage + (it.throwableProxy?.message ?: "") }
        assertTrue("expected the refusal to be logged", logged.contains("/v1/fundi/mpesa/transactions"))
        listOf("0712345678", "0799111222").forEach { assertFalse("$it reached the log:\n$logged", logged.contains(it)) }
        assertNull(store.transactions.values.firstOrNull())
    }
}
