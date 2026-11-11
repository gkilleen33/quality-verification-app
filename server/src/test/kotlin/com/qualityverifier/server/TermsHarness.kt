package com.qualityverifier.server

import com.qualityverifier.data.prompts.PromptRepository
import com.qualityverifier.domain.Audience
import com.qualityverifier.server.auth.AccessTokens
import com.qualityverifier.server.blobs.BlobStore
import com.qualityverifier.server.chat.ClaudeClient
import com.qualityverifier.server.db.AppSettings
import com.qualityverifier.server.db.AuthStore
import com.qualityverifier.server.db.ChatStore
import com.qualityverifier.server.db.FeedbackStore
import com.qualityverifier.server.db.FundiStore
import com.qualityverifier.server.db.UserRow
import com.qualityverifier.server.terms.Agreement
import com.qualityverifier.server.terms.ConsentAction
import com.qualityverifier.server.terms.ConsentKind
import com.qualityverifier.server.terms.ConsentRecord
import com.qualityverifier.server.terms.ConsentStore
import com.qualityverifier.server.terms.TermsFile
import com.qualityverifier.server.terms.TermsGate
import com.qualityverifier.server.terms.TermsLanguage
import com.qualityverifier.server.terms.TermsTexts
import com.qualityverifier.text.sha256Hex
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import java.io.File
import java.lang.reflect.Proxy

/**
 * A stand-in for an interface whose every method fails, except those given [answers].
 *
 * The terms tests mount the real routes, and a refused route must never reach its store:
 * [calls] records every method that was reached, so a test can prove the route body did
 * not run. A stub that throws rather than a fake that answers, because an open route that
 * reaches one shows only that the gate let it through, which is all those tests ask.
 */
class Stub<T : Any>(type: Class<T>, private val answers: Map<String, (Array<out Any?>) -> Any?> = emptyMap()) {
    val calls = mutableListOf<String>()

    val instance: T = type.cast(
        Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { proxy, method, args ->
            when (method.name) {
                "toString" -> "Stub(${type.simpleName})"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args?.firstOrNull()
                else -> {
                    calls += method.name
                    val answer = answers[method.name]
                        ?: throw UnsupportedOperationException("${type.simpleName}.${method.name} is not stubbed")
                    // A suspend function's last argument is its continuation; a value
                    // returned straight away is its result.
                    answer(args ?: emptyArray<Any?>())
                }
            }
        }
    )
}

/** An in-memory `consents` table: append-only, newest row last. */
class FakeConsentStore : ConsentStore {
    data class Row(val userId: String, val kind: ConsentKind, val action: ConsentAction, val agreement: Agreement?)

    val rows = mutableListOf<Row>()

    override suspend fun current(userId: String): Map<ConsentKind, ConsentRecord> =
        rows.filter { it.userId == userId }
            .groupBy { it.kind }
            .mapValues { (_, list) -> list.last().let { ConsentRecord(it.action, it.agreement?.version) } }

    override suspend fun agree(userId: String, agreement: Agreement) {
        ConsentKind.entries.forEach { rows += Row(userId, it, ConsentAction.AGREED, agreement) }
    }

    override suspend fun withdraw(userId: String, audience: Audience, kind: ConsentKind, appVersion: Int): Boolean {
        val newest = rows.lastOrNull { it.userId == userId && it.kind == kind } ?: return false
        if (newest.action != ConsentAction.AGREED) return false
        rows += Row(userId, kind, ConsentAction.WITHDRAWN, null)
        return true
    }
}

/** Everything the terms tests share: accounts, the text, the settings, the gate. */
class TermsWorld {
    val fundi = UserRow("00000000-0000-4000-8000-00000000f001", "Wanjiru", "individual", null, false, audience = Audience.FUNDI)
    val buyer = UserRow("00000000-0000-4000-8000-00000000b001", "Otieno", "individual", null, false, audience = Audience.BUYER)
    private val users = listOf(fundi, buyer).associateBy { it.id }

    val englishBytes = "# Fundi Bora: Terms of Use\n\nVersion: 2026-11\n\n## 1. About these terms\n\nText.\n"
        .toByteArray()
    val englishSha = sha256Hex(englishBytes)

    /** A later version, published so a test can raise terms_version to it. */
    private val laterBytes = "# Fundi Bora: Terms of Use\n\nVersion: 2026-12\n\nChanged.\n".toByteArray()
    val texts = TermsTexts(
        mapOf(
            TermsTexts.Key(Audience.FUNDI, "2026-11", TermsLanguage.ENGLISH) to TermsFile(englishBytes, englishSha),
            TermsTexts.Key(Audience.FUNDI, "2026-12", TermsLanguage.ENGLISH) to
                TermsFile(laterBytes, sha256Hex(laterBytes)),
        )
    )

    val settingsStore = FakeSettingsStore(
        mapOf(
            (Audience.FUNDI to "terms_gate") to "off",
            (Audience.BUYER to "terms_gate") to "off",
            (Audience.FUNDI to "terms_version") to "2026-11",
        )
    )
    val clock = TestClock()
    val settings = AppSettings(settingsStore, now = { clock.now })
    val consents = FakeConsentStore()
    val gate = TermsGate(settings, consents, texts, now = { clock.now })
    val terms = Terms(gate, consents, texts)

    val auth = Stub(
        AuthStore::class.java,
        mapOf("findUser" to { args: Array<out Any?> -> users[args[0] as String] }),
    )
    val chat = Stub(ChatStore::class.java)
    val fundiStore = Stub(FundiStore::class.java)
    private val claude = Stub(ClaudeClient::class.java)
    private val prompts = Stub(PromptRepository::class.java)
    private val feedback = Stub(FeedbackStore::class.java)

    /** Calls that reached a route's own stores. The gate's own account lookup is not one. */
    val routeCalls: List<String> get() = chat.calls + fundiStore.calls + feedback.calls + claude.calls

    /** Switches [audience]'s gate and makes the change visible at once. */
    fun switchGate(audience: Audience, on: Boolean) {
        settingsStore.set(audience, "terms_gate", if (on) "on" else "off")
        settings.clear(audience)
    }

    /** Raises Fundi Bora's terms_version, as set-setting.sh would. */
    fun raiseVersion(version: String): TermsWorld {
        settingsStore.set(Audience.FUNDI, "terms_version", version)
        settings.clear(Audience.FUNDI)
        return this
    }

    fun ApplicationTestBuilder.mount(blobsDir: File, withTerms: Boolean = true) = run {
        application {
            module(
                version = "test",
                database = null,
                auth = Auth(auth.instance, AccessTokens(KEY)),
                chat = Chat(chat.instance, BlobStore(blobsDir), claude.instance, prompts.instance, feedback.instance),
                fundi = Fundi(fundiStore.instance),
                settings = settings,
                terms = if (withTerms) terms else null,
            )
        }
        createClient { install(ClientContentNegotiation) { json() } }
    }

    companion object {
        const val KEY = "a-signing-key-long-enough-to-be-real"

        fun HttpRequestBuilder.signedInAs(user: UserRow) {
            header(HttpHeaders.Authorization, "Bearer ${AccessTokens(KEY).issue(user.id).token}")
        }

        fun HttpRequestBuilder.appVersion(code: Int = 5) {
            header("X-App-Version", code.toString())
        }
    }
}
