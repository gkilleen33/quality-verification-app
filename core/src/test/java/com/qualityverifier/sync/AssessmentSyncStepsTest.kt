package com.qualityverifier.sync

import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.db.SessionImageStore
import com.qualityverifier.data.session.SessionRepository
import com.qualityverifier.data.sync.AssessmentSync
import com.qualityverifier.data.sync.SyncClient
import com.qualityverifier.data.sync.SyncStep
import com.qualityverifier.data.workers.StudioRepository
import com.qualityverifier.data.workers.WorkerChange
import com.qualityverifier.data.workers.WorkerRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.TimeUnit

/**
 * One failing step of the push no longer stops the others (v0.6.0, release scope row 6).
 *
 * A maker's piece names fail to read from the phone's database (a full disk, say). Before
 * this, the push ended there, and the roster written after it waited another cycle. Now the
 * names step is named as failed, the worker still reaches the server, and the screens can
 * say when the last sync failed.
 */
class AssessmentSyncStepsTest {

    private lateinit var server: MockWebServer
    private val markedSynced = mutableListOf<String>()

    @Before
    fun setUp() {
        server = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when {
                    request.method == "PUT" && request.path!!.startsWith("/v1/fundi/workers/") ->
                        MockResponse().setResponseCode(200).setBody("{}")
                    // Everything the pull asks for: the server is unwell.
                    else -> MockResponse().setResponseCode(503)
                }
            }
            start()
        }
    }

    @After
    fun tearDown() = server.shutdown()

    private fun sync(): AssessmentSync {
        val tokens = TokenProvider(Tokens, { RefreshOutcome.Unavailable })
        val client = SyncClient(
            client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
            tokens = tokens,
            baseUrl = server.url("/").toString(),
            json = Json { ignoreUnknownKeys = true },
            io = Dispatchers.Unconfined,
        )
        val sessions = stub<SessionRepository>(
            "pendingRemoteDeletes" to { emptyList<String>() },
            "pendingTesterFeedback" to { emptyList<Any>() },
            "unsyncedPieceLabels" to { throw IllegalStateException("disk full") },
            "unsyncedPieceStudios" to { emptyList<Any>() },
        )
        val workers = stub<WorkerRepository>(
            "unsynced" to { listOf(WorkerChange("w1", "Kamau", archived = false, asOf = 1L)) },
            "markSynced" to { markedSynced += "w1"; Unit },
            "pendingSessions" to { emptyList<Any>() },
        )
        val studios = stub<StudioRepository>("unsynced" to { emptyList<Any>() })
        return AssessmentSync(
            client = client,
            sessions = sessions,
            images = stub<SessionImageStore>(),
            tokens = Tokens,
            workers = workers,
            studios = studios,
            rosterEnabled = true,
            io = Dispatchers.Unconfined,
            now = { 1_000L },
        )
    }

    @Test
    fun `a step that throws is named, and the steps after it still send`() = runTest {
        val pushed = sync().push()
        assertEquals(listOf(SyncStep.PIECE_NAMES), pushed.failed)
        assertEquals("the worker went after the names failed", listOf("w1"), markedSynced)
        assertEquals(1, pushed.rosterWrites)
    }

    @Test
    fun `the status says when the last sync failed and what did not go`() = runTest {
        val sync = sync()
        assertNull(sync.status.value.failedAt)
        sync.run()
        val status = sync.status.value
        assertNotNull(status.failedAt)
        assertEquals(1_000L, status.failedAt)
        assertTrue(status.failed.containsAll(listOf(SyncStep.PIECE_NAMES, SyncStep.PULL)))
    }

    /** An interface whose named methods answer, suspend ones included, and whose others fail. */
    private inline fun <reified T : Any> stub(vararg answers: Pair<String, () -> Any?>): T {
        val byName = answers.toMap()
        return Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { proxy, method, _ ->
            when (method.name) {
                "toString" -> "stub ${T::class.java.simpleName}"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> false
                else -> byName[method.name]?.invoke()
                    ?: throw UnsupportedOperationException("${T::class.java.simpleName}.${method.name} is not stubbed")
            }
        } as T
    }

    private object Tokens : TokenStore {
        override fun accessToken() = "access"
        override fun refreshToken() = "refresh"
        override fun userId() = "u1"
        override fun accessTokenExpiresAt() = System.currentTimeMillis() + 60 * 60 * 1000
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) = Unit
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun clear() = Unit
    }
}
