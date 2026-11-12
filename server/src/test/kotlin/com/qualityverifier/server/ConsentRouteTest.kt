package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.TermsWorld.Companion.appVersion
import com.qualityverifier.server.TermsWorld.Companion.signedInAs
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.terms.ConsentAction
import com.qualityverifier.server.terms.ConsentKind
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
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

/**
 * Recording agreement and withdrawal, what `/v1/me` reports, and the text route.
 */
class ConsentRouteTest {

    @get:Rule
    val folder = TemporaryFolder()

    private suspend fun HttpClient.consent(
        world: TermsWorld,
        body: String,
        withVersionHeader: Boolean = true,
        user: UserRow = world.fundi,
    ): HttpResponse = post("/v1/me/consents") {
        signedInAs(user)
        if (withVersionHeader) appVersion()
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private fun both(world: TermsWorld, version: String = "2026-11", sha: String = world.englishSha) =
        """{"version":"$version","language":"en","text_sha256":"$sha","kinds":["terms","transfer"]}"""

    private suspend fun HttpClient.withdraw(world: TermsWorld, kind: String = "transfer"): HttpResponse =
        post("/v1/me/consents/withdrawal") {
            signedInAs(world.fundi)
            appVersion()
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"$kind"}""")
        }

    private suspend fun HttpClient.me(world: TermsWorld, user: UserRow = world.fundi): JsonObject =
        Json.parseToJsonElement(get("/v1/me") { signedInAs(user) }.bodyAsText()).jsonObject

    @Test
    fun `an agreement writes one row per kind, with the audience from the account`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        assertEquals(HttpStatusCode.NoContent, client.consent(world, both(world)).status)

        assertEquals(listOf(ConsentKind.TERMS, ConsentKind.TRANSFER), world.consents.rows.map { it.kind })
        world.consents.rows.forEach { row ->
            assertEquals(ConsentAction.AGREED, row.action)
            assertEquals(Audience.FUNDI, row.agreement?.audience)
            assertEquals("2026-11", row.agreement?.version)
            assertEquals(world.englishSha, row.agreement?.textSha256)
            assertEquals(5, row.agreement?.appVersion)
        }
    }

    @Test
    fun `a request missing either kind is refused and writes nothing`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        listOf("""["terms"]""", """["transfer"]""", "[]", """["terms","terms"]""", """["terms","transfer","research"]""")
            .forEach { kinds ->
                val body = """{"version":"2026-11","language":"en","text_sha256":"${world.englishSha}","kinds":$kinds}"""
                assertEquals("kinds $kinds", HttpStatusCode.BadRequest, client.consent(world, body).status)
            }
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `an audience in the body is refused, not believed`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val body = both(world).removeSuffix("}") + ""","audience":"buyer"}"""
        assertEquals(HttpStatusCode.BadRequest, client.consent(world, body).status)
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `a stale version is a conflict`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val response = client.consent(world, both(world, version = "2026-10"))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("terms_version_changed"))
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `a hash that is not the published text's is a conflict`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val response = client.consent(world, both(world, sha = "b".repeat(64)))

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertTrue(response.bodyAsText().contains("terms_text_mismatch"))
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `a language with no published text is a conflict`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val body = both(world).replace("\"language\":\"en\"", "\"language\":\"sw\"")
        assertEquals(HttpStatusCode.Conflict, client.consent(world, body).status)
    }

    @Test
    fun `no app version header is refused`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        assertEquals(HttpStatusCode.BadRequest, client.consent(world, both(world), withVersionHeader = false).status)
        assertTrue(world.consents.rows.isEmpty())
    }

    @Test
    fun `a Kagua account has no terms to agree to`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        assertEquals(HttpStatusCode.Conflict, client.consent(world, both(world), user = world.buyer).status)
    }

    @Test
    fun `only the transfer agreement can be withdrawn`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }
        client.consent(world, both(world))

        assertEquals(HttpStatusCode.BadRequest, client.withdraw(world, kind = "terms").status)
        assertEquals(2, world.consents.rows.size)
    }

    @Test
    fun `a withdrawal writes one row, and a second writes nothing`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }
        client.consent(world, both(world))

        assertEquals(HttpStatusCode.NoContent, client.withdraw(world).status)
        assertEquals(HttpStatusCode.NoContent, client.withdraw(world).status)

        val withdrawals = world.consents.rows.filter { it.action == ConsentAction.WITHDRAWN }
        assertEquals(1, withdrawals.size)
        assertEquals(ConsentKind.TRANSFER, withdrawals.single().kind)
    }

    @Test
    fun `terms_required is true for a fundi without agreements while the gate is off`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val me = client.me(world)

        assertTrue(me["terms_required"]!!.jsonPrimitive.boolean)
        assertFalse(me["transfer_agreed"]!!.jsonPrimitive.boolean)
        val terms = me["terms"]!!.jsonObject
        assertEquals("2026-11", terms["version"]!!.jsonPrimitive.content)
        val english = terms["languages"]!!.jsonArray.single().jsonObject
        assertEquals("en", english["language"]!!.jsonPrimitive.content)
        assertEquals(world.englishSha, english["sha256"]!!.jsonPrimitive.content)
        assertEquals("terms/fundi/2026-11/en", english["path"]!!.jsonPrimitive.content)
    }

    @Test
    fun `after agreeing on one phone, a second phone is not asked again`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }
        client.consent(world, both(world))

        // A second phone is a second token for the same account.
        val me = client.me(world)

        assertFalse(me["terms_required"]!!.jsonPrimitive.boolean)
        assertTrue(me["transfer_agreed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `transfer_agreed follows the newest row`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }
        client.consent(world, both(world))
        client.withdraw(world)

        val me = client.me(world)

        assertFalse(me["transfer_agreed"]!!.jsonPrimitive.boolean)
        assertTrue(me["terms_required"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a Kagua account has no terms on me`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val me = client.me(world, user = world.buyer)

        assertFalse(me["terms_required"]!!.jsonPrimitive.boolean)
        assertTrue(me["terms"] == null || me["terms"].toString() == "null")
    }

    @Test
    fun `without the terms bundle, me reports no terms as before`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder(), withTerms = false) }

        val me = client.me(world)

        assertFalse(me["terms_required"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the text route serves the published bytes with their hash`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        val response = client.get("/terms/fundi/2026-11/en")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(String(world.englishBytes), response.bodyAsText())
        assertEquals("\"${world.englishSha}\"", response.headers[HttpHeaders.ETag])
        assertTrue(response.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/markdown"))
    }

    @Test
    fun `an unpublished text is not found`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        assertEquals(HttpStatusCode.NotFound, client.get("/terms/fundi/2026-11/sw").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/terms/buyer/2026-11/en").status)
        assertEquals(HttpStatusCode.NotFound, client.get("/terms/fundi/2026-11/fr").status)
        assertNull(world.consents.rows.firstOrNull())
    }
}
