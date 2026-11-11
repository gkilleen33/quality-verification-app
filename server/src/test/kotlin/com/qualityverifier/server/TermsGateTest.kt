package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.TermsWorld.Companion.appVersion
import com.qualityverifier.server.TermsWorld.Companion.signedInAs
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.terms.TermsGate
import io.ktor.client.HttpClient
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.plugin
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The terms gate on the real routes.
 *
 * With Fundi Bora's gate on, an account that has not agreed to both parts of the current
 * terms is refused with `403 terms_required` on every authenticated route except the open
 * list, and the refused route's body never runs. With the gate off, nothing changes for
 * any phone. A Kagua account is never refused: Kagua has no terms.
 */
class TermsGateTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val sha = "a".repeat(64)
    private val uuid = "11111111-1111-4111-8111-111111111111"

    /** Every authenticated route that collects or changes data, with a path to call it by. */
    private val refused = listOf(
        HttpMethod.Post to "/v1/chat",
        HttpMethod.Post to "/v1/fundi/chat",
        HttpMethod.Put to "/v1/blobs/$sha",
        HttpMethod.Put to "/v1/fundi/profile",
        HttpMethod.Put to "/v1/fundi/workshop-location",
        HttpMethod.Put to "/v1/fundi/workers/$uuid",
        HttpMethod.Put to "/v1/fundi/pieces/$uuid",
        HttpMethod.Put to "/v1/sessions/$uuid/workers",
        HttpMethod.Post to "/v1/tester-feedback",
    )

    /** The open list, called by path. */
    private val open = listOf(
        HttpMethod.Get to "/v1/me",
        HttpMethod.Post to "/v1/me/consents",
        HttpMethod.Post to "/v1/me/consents/withdrawal",
        HttpMethod.Post to "/v1/auth/password",
        HttpMethod.Delete to "/v1/account",
        HttpMethod.Get to "/v1/sessions",
        HttpMethod.Get to "/v1/sessions/$uuid",
        HttpMethod.Delete to "/v1/sessions/$uuid",
        HttpMethod.Get to "/v1/blobs/$sha",
        HttpMethod.Head to "/v1/blobs/$sha",
        HttpMethod.Get to "/v1/fundi/profile",
        HttpMethod.Get to "/v1/fundi/workers",
    )

    private suspend fun HttpClient.hit(method: HttpMethod, path: String, user: UserRow): HttpResponse =
        request(path) {
            this.method = method
            signedInAs(user)
            appVersion()
            if (method == HttpMethod.Post || method == HttpMethod.Put) {
                contentType(ContentType.Application.Json)
                setBody("{}")
            }
        }

    private suspend fun HttpResponse.isTermsRefusal(): Boolean =
        status == HttpStatusCode.Forbidden && bodyAsText().contains("terms_required")

    private suspend fun HttpClient.agree(world: TermsWorld, version: String = "2026-11", sha256: String = world.englishSha) {
        val response = request("/v1/me/consents") {
            method = HttpMethod.Post
            signedInAs(world.fundi)
            appVersion()
            contentType(ContentType.Application.Json)
            setBody("""{"version":"$version","language":"en","text_sha256":"$sha256","kinds":["terms","transfer"]}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `with the gate on, each refused route answers terms_required and its body never runs`() = testApplication {
        val world = TermsWorld().apply { switchGate(Audience.FUNDI, on = true) }
        val client = with(world) { mount(folder.newFolder()) }

        refused.forEach { (method, path) ->
            val response = client.hit(method, path, world.fundi)
            assertTrue("$method $path was not refused: ${response.status}", response.isTermsRefusal())
        }
        assertEquals("a refused route reached its store", emptyList<String>(), world.routeCalls)
    }

    @Test
    fun `with the gate on, each open route answers before agreeing`() = testApplication {
        val world = TermsWorld().apply { switchGate(Audience.FUNDI, on = true) }
        val client = with(world) { mount(folder.newFolder()) }

        open.forEach { (method, path) ->
            // Any answer but the refusal: the stores behind these are stubs, so some
            // answer 500, which still shows the gate let the request through.
            val response = client.hit(method, path, world.fundi)
            assertTrue("$method $path was refused before agreeing", !response.isTermsRefusal())
        }
    }

    @Test
    fun `after agreeing, the refused routes answer`() = testApplication {
        val world = TermsWorld().apply { switchGate(Audience.FUNDI, on = true) }
        val client = with(world) { mount(folder.newFolder()) }

        client.agree(world)

        refused.forEach { (method, path) ->
            val response = client.hit(method, path, world.fundi)
            assertTrue("$method $path still refused after agreeing", !response.isTermsRefusal())
        }
    }

    @Test
    fun `terms agreed with the transfer missing is refused`() = testApplication {
        val world = TermsWorld().apply { switchGate(Audience.FUNDI, on = true) }
        val client = with(world) { mount(folder.newFolder()) }
        // Only reachable by a row written outside the route, which always writes both.
        world.consents.rows += FakeConsentStore.Row(
            world.fundi.id,
            com.qualityverifier.server.terms.ConsentKind.TERMS,
            com.qualityverifier.server.terms.ConsentAction.AGREED,
            com.qualityverifier.server.terms.Agreement(
                Audience.FUNDI, "2026-11",
                com.qualityverifier.server.terms.TermsLanguage.ENGLISH, world.englishSha, 5,
            ),
        )

        assertTrue(client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi).isTermsRefusal())
    }

    @Test
    fun `a withdrawn transfer agreement is refused until agreed again`() = testApplication {
        val world = TermsWorld().apply { switchGate(Audience.FUNDI, on = true) }
        val client = with(world) { mount(folder.newFolder()) }
        client.agree(world)
        assertTrue(!client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi).isTermsRefusal())

        val withdrawal = client.request("/v1/me/consents/withdrawal") {
            method = HttpMethod.Post
            signedInAs(world.fundi)
            appVersion()
            contentType(ContentType.Application.Json)
            setBody("""{"kind":"transfer"}""")
        }
        assertEquals(HttpStatusCode.NoContent, withdrawal.status)
        assertTrue(
            "refused at once, not after the cache expires",
            client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi).isTermsRefusal(),
        )

        client.agree(world)
        assertTrue(!client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi).isTermsRefusal())
    }

    @Test
    fun `a raised terms version refuses until both are agreed again`() = testApplication {
        val world = TermsWorld().apply { switchGate(Audience.FUNDI, on = true) }
        val client = with(world) { mount(folder.newFolder()) }
        client.agree(world)

        world.raiseVersion("2026-12")
        world.gate.forget(world.fundi.id)
        assertTrue(client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi).isTermsRefusal())

        val later = world.texts.file(Audience.FUNDI, "2026-12", com.qualityverifier.server.terms.TermsLanguage.ENGLISH)!!
        client.agree(world, version = "2026-12", sha256 = later.sha256)
        assertTrue(!client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi).isTermsRefusal())
    }

    @Test
    fun `a Kagua account is never refused`() = testApplication {
        val world = TermsWorld().apply {
            switchGate(Audience.FUNDI, on = true)
            // Even with Kagua's own gate on: Kagua has no terms_version, so nothing is required.
            switchGate(Audience.BUYER, on = true)
        }
        val client = with(world) { mount(folder.newFolder()) }

        val response = client.hit(HttpMethod.Post, "/v1/chat", world.buyer)
        assertTrue("a Kagua account was refused", !response.isTermsRefusal())
    }

    @Test
    fun `with the gate off, every route answers as it did before`() = testApplication {
        val world = TermsWorld()
        val client = with(world) { mount(folder.newFolder()) }

        (refused + open).forEach { (method, path) ->
            val response = client.hit(method, path, world.fundi)
            assertTrue("$method $path was refused with the gate off", !response.isTermsRefusal())
        }
    }

    @Test
    fun `a terms version with no text is no terms, so nobody is locked out`() = testApplication {
        val world = TermsWorld().apply {
            switchGate(Audience.FUNDI, on = true)
            raiseVersion("2026-13")
        }
        val client = with(world) { mount(folder.newFolder()) }

        val response = client.hit(HttpMethod.Post, "/v1/fundi/chat", world.fundi)
        assertTrue("a typo in terms_version locked the account out", !response.isTermsRefusal())
    }

    @Test
    fun `every authenticated route is either open or known to be refused`() = testApplication {
        val world = TermsWorld()
        with(world) { mount(folder.newFolder()) }
        lateinit var root: RoutingNode
        application { root = plugin(RoutingRoot) }
        startApplication()

        val found = mutableSetOf<Pair<String, String>>()
        fun walk(node: RoutingNode, path: String, authenticated: Boolean) {
            val selector = node.selector.toString()
            var here = path
            var authed = authenticated
            when {
                selector.startsWith("(method:") -> if (authed) {
                    found += selector.removePrefix("(method:").removeSuffix(")") to path.ifEmpty { "/" }
                }
                selector.contains("authenticate") -> authed = true
                selector.isEmpty() || selector.startsWith("(") || selector == "/" -> Unit
                else -> here = "$path/$selector"
            }
            node.children.forEach { walk(it, here, authed) }
        }
        walk(root, "", false)

        assertTrue("found no authenticated routes; the walk is broken", found.size > 10)
        val sample = { template: String -> template.replace(Regex("""\{[^}]+\}"""), "x") }
        val refusedTemplates = setOf(
            "POST" to "/v1/chat",
            "POST" to "/v1/fundi/chat",
            "PUT" to "/v1/blobs/x",
            "PUT" to "/v1/fundi/profile",
            "PUT" to "/v1/fundi/workshop-location",
            "PUT" to "/v1/fundi/workers/x",
            "PUT" to "/v1/fundi/pieces/x",
            "PUT" to "/v1/sessions/x/workers",
            "POST" to "/v1/tester-feedback",
        )
        val unclassified = found.filterNot { (method, template) ->
            TermsGate.isOpen(HttpMethod.parse(method), sample(template)) ||
                (method to sample(template)) in refusedTemplates
        }
        assertEquals(
            "these authenticated routes are neither on TermsGate.OPEN nor in this test's refused " +
                "list. Decide whether an account that has not agreed may use each, then add it to one",
            emptyList<Pair<String, String>>(),
            unclassified,
        )
    }

    @Test
    fun `the open list matches its templates and nothing beside them`() {
        assertTrue(TermsGate.isOpen(HttpMethod.Get, "/v1/sessions/$uuid"))
        assertTrue(TermsGate.isOpen(HttpMethod.Get, "/v1/me/"))
        assertTrue(!TermsGate.isOpen(HttpMethod.Put, "/v1/sessions/$uuid/workers"))
        assertTrue(!TermsGate.isOpen(HttpMethod.Post, "/v1/me"))
        assertTrue(!TermsGate.isOpen(HttpMethod.Get, "/v1/sessions/$uuid/extra"))
        assertNotEquals(0, TermsGate.OPEN.size)
    }
}
