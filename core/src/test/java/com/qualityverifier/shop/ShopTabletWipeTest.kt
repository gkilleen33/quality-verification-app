package com.qualityverifier.shop

import android.content.ContextWrapper
import com.qualityverifier.data.auth.RefreshOutcome
import com.qualityverifier.data.auth.TokenProvider
import com.qualityverifier.data.auth.TokenStore
import com.qualityverifier.data.db.ImageFileStore
import com.qualityverifier.data.shop.PendingTabletEnd
import com.qualityverifier.data.shop.ShopTabletWipe
import com.qualityverifier.data.shop.TabletEndQueue
import com.qualityverifier.data.sync.PasswordCheck
import com.qualityverifier.data.sync.SyncClient
import com.qualityverifier.domain.TabletEnd
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The shop tablet's wipe (release scope row 12).
 *
 * Against the real SyncClient and a stand-in server, so "no server delete" is a fact about the
 * requests that left the phone, and against the real image store in a temporary folder, so
 * "every photo file" is a fact about the disk.
 */
class ShopTabletWipeTest {

    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var server: MockWebServer

    @Before
    fun start() {
        server = MockWebServer().also { it.start() }
    }

    @After
    fun stop() {
        server.shutdown()
    }

    private object SignedIn : TokenStore {
        override fun accessToken() = "access"
        override fun refreshToken() = "refresh"
        override fun accessTokenExpiresAt() = Long.MAX_VALUE
        override fun userId() = "shop"
        override fun isTester() = false
        override fun setTester(value: Boolean) = Unit
        override fun save(accessToken: String, expiresInSeconds: Long, refreshToken: String, userId: String) = Unit
        override fun clear() = Unit
    }

    private class MemoryQueue : TabletEndQueue {
        val items = mutableListOf<PendingTabletEnd>()
        override fun add(item: PendingTabletEnd) {
            if (items.none { it.sessionId == item.sessionId }) items += item
        }
        override fun all() = items.toList()
        override fun remove(sessionId: String) {
            items.removeAll { it.sessionId == sessionId }
        }
    }

    /** The tablet as the app builds it, with the tables in a set and the photos on disk. */
    private inner class Tablet(sessions: Set<String>, private val clock: () -> Long = { 1_000L }) {
        val rows = sessions.toMutableSet()
        val images = ImageFileStore(ContextWrapper(null), filesDir = { folder.root })
        val queue = MemoryQueue()
        val client = SyncClient(
            client = OkHttpClient(),
            tokens = TokenProvider(store = SignedIn, refresher = { RefreshOutcome.Rejected }),
            baseUrl = server.url("/").toString(),
            json = Json { ignoreUnknownKeys = true },
        )
        val wipe = ShopTabletWipe(
            localSessionIds = { rows.toSet() },
            clearLocal = {
                rows.clear()
                images.deleteAll()
            },
            pending = queue,
            api = client,
            now = clock,
        )

        /** Photos for each assessment, written where the camera writes them, with their metadata. */
        fun photograph(): List<File> = rows.flatMap { id ->
            (1..3).map { n ->
                images.newImageFile(id).apply {
                    writeBytes(ByteArray(32) { n.toByte() })
                    File(parentFile, "$nameWithoutExtension.meta.json").writeText("{}")
                }
            }
        }
    }

    private fun requests(): List<okhttp3.mockwebserver.RecordedRequest> =
        generateSequence { server.takeRequest(100, TimeUnit.MILLISECONDS) }.toList()

    @Test
    fun `row 12 - the wipe calls no server delete`() = runTest {
        val tablet = Tablet(setOf(A, B))
        tablet.photograph()
        repeat(2) { server.enqueue(MockResponse().setResponseCode(204)) }

        tablet.wipe.wipe(TabletEnd.DONE)

        val sent = requests()
        assertEquals(2, sent.size)
        assertTrue(sent.joinToString { "${it.method} ${it.path}" }, sent.none { it.method == "DELETE" })
        assertTrue(sent.all { it.method == "PUT" && it.path!!.endsWith("/tablet-end") })
        assertEquals(setOf("/v1/sessions/$A/tablet-end", "/v1/sessions/$B/tablet-end"), sent.map { it.path }.toSet())
    }

    @Test
    fun `row 12 - each of the four triggers clears the local copy and every photo file`() = runTest {
        TabletEnd.entries.forEach { end ->
            val tablet = Tablet(setOf(A))
            val photos = tablet.photograph()
            assertTrue(photos.all { it.exists() })
            server.enqueue(MockResponse().setResponseCode(204))

            assertEquals(end.id, 1, tablet.wipe.wipe(end))

            assertTrue("${end.id}: the assessment row is gone", tablet.rows.isEmpty())
            assertTrue("${end.id}: a photo is left", photos.none { it.exists() })
            val left = File(folder.root, "images").walkTopDown().filter { it.isFile }.toList()
            assertTrue("${end.id}: files left: $left", left.isEmpty())
            val sent = requests().single()
            assertEquals("""{"end":"${end.id}"}""", sent.body.readUtf8())
            assertTrue(tablet.queue.items.isEmpty())
        }
    }

    @Test
    fun `row 12 - a tablet with nothing on it reports nothing`() = runTest {
        val tablet = Tablet(emptySet())

        assertEquals(0, tablet.wipe.wipe(TabletEnd.IDLE))

        assertTrue(requests().isEmpty())
    }

    @Test
    fun `row 12 - an end the server cannot place yet is kept, then sent`() = runTest {
        // The customer left while the opening turn was on its way: the report reaches the
        // server first and is answered 404. The local copy goes at once all the same.
        val tablet = Tablet(setOf(A))
        tablet.photograph()
        server.enqueue(MockResponse().setResponseCode(404))

        tablet.wipe.wipe(TabletEnd.LEFT_APP)

        assertTrue(tablet.rows.isEmpty())
        assertEquals(listOf(A), tablet.queue.items.map { it.sessionId })

        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(1, tablet.wipe.flush())
        assertTrue(tablet.queue.items.isEmpty())
    }

    @Test
    fun `row 12 - an end the server never places is dropped after a day`() = runTest {
        var now = 1_000L
        val tablet = Tablet(setOf(A)) { now }
        server.enqueue(MockResponse().setResponseCode(404))
        tablet.wipe.wipe(TabletEnd.SCREEN_LOCK)
        assertEquals(1, tablet.queue.items.size)

        now += 25L * 60 * 60 * 1000
        server.enqueue(MockResponse().setResponseCode(404))
        tablet.wipe.flush()

        assertTrue(tablet.queue.items.isEmpty())
    }

    @Test
    fun `row 12 - the owner's password check reads the server's answer`() = runTest {
        // The owner's controls open only on MATCHES; every other answer keeps them shut.
        val client = Tablet(emptySet()).client
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":"wrong_password"}"""))
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"locked"}"""))
        server.enqueue(MockResponse().setResponseCode(503))

        assertEquals(PasswordCheck.MATCHES, client.checkPassword("the shop password"))
        assertEquals(PasswordCheck.WRONG, client.checkPassword("a guess"))
        assertEquals(PasswordCheck.LOCKED, client.checkPassword("another guess"))
        assertEquals(PasswordCheck.UNAVAILABLE, client.checkPassword("the shop password"))

        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/v1/me/password-check", first.path)
        assertEquals("""{"password":"the shop password"}""", first.body.readUtf8())
    }

    private companion object {
        const val A ="2ff77920-3928-46e5-8a77-5e16c1e901c6"
        const val B = "3aa88931-4a39-47f6-9b88-6f27d2f012d7"
    }
}
