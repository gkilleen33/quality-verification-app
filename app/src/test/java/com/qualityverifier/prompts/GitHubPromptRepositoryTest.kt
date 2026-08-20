package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.data.prompts.GitHubPromptRepository
import com.qualityverifier.data.prompts.PromptCache
import com.qualityverifier.domain.ItemType
import kotlinx.coroutines.test.runTest
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
import java.util.concurrent.TimeUnit

class GitHubPromptRepositoryTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private var clock = 5_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun repository(ttlHours: Long = 24) = GitHubPromptRepository(
        client = OkHttpClient.Builder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .build(),
        cache = cache(),
        baseUrl = server.url("/prompts/").toString(),
        ttlMillis = TimeUnit.HOURS.toMillis(ttlHours),
    )

    private fun cache() = PromptCache(temp.root, now = { clock })

    @Test
    fun `fetches master and item prompt and joins them`() = runTest {
        server.enqueue(MockResponse().setBody("MASTER TEXT"))
        server.enqueue(MockResponse().setBody("TABLE TEXT"))

        val prompt = repository().systemPromptFor(ItemType.WOODEN_TABLE)

        assertEquals("MASTER TEXT\n\nTABLE TEXT", prompt)
        assertEquals("/prompts/master.txt", server.takeRequest().path)
        assertEquals("/prompts/items/wooden-table.txt", server.takeRequest().path)
    }

    @Test
    fun `an empty item file is valid and is not treated as a failure`() = runTest {
        // This is the shipped state: items are empty placeholders.
        server.enqueue(MockResponse().setBody("MASTER TEXT"))
        server.enqueue(MockResponse().setBody(""))

        assertEquals("MASTER TEXT", repository().systemPromptFor(ItemType.OTHER))
    }

    @Test
    fun `a missing item file falls back to no item prompt`() = runTest {
        server.enqueue(MockResponse().setBody("MASTER TEXT"))
        server.enqueue(MockResponse().setResponseCode(404))

        assertEquals("MASTER TEXT", repository().systemPromptFor(ItemType.WOODEN_BED))
    }

    @Test
    fun `with no network and no cache the compiled-in master prompt is used`() = runTest {
        server.shutdown()

        val prompt = repository().systemPromptFor(ItemType.WOODEN_CHAIR)

        assertEquals(DefaultPrompts.MASTER.trimEnd(), prompt)
        assertTrue(prompt.contains("furniture quality verification assistant"))
    }

    @Test
    fun `a fresh cache is served without hitting the network`() = runTest {
        val repo = repository()
        server.enqueue(MockResponse().setBody("MASTER V1"))
        server.enqueue(MockResponse().setBody("ITEM V1"))
        repo.systemPromptFor(ItemType.WOODEN_TABLE)
        assertEquals(2, server.requestCount)

        clock += TimeUnit.HOURS.toMillis(1)
        assertEquals("MASTER V1\n\nITEM V1", repo.systemPromptFor(ItemType.WOODEN_TABLE))
        // Still 2: nothing new was requested.
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `a stale cache is refreshed from the network`() = runTest {
        val repo = repository()
        server.enqueue(MockResponse().setBody("MASTER V1"))
        server.enqueue(MockResponse().setBody("ITEM V1"))
        repo.systemPromptFor(ItemType.WOODEN_TABLE)

        clock += TimeUnit.HOURS.toMillis(25)
        server.enqueue(MockResponse().setBody("MASTER V2"))
        server.enqueue(MockResponse().setBody("ITEM V2"))

        assertEquals("MASTER V2\n\nITEM V2", repo.systemPromptFor(ItemType.WOODEN_TABLE))
    }

    @Test
    fun `a stale cache is still used when the network is unreachable`() = runTest {
        val repo = repository()
        server.enqueue(MockResponse().setBody("MASTER V1"))
        server.enqueue(MockResponse().setBody("ITEM V1"))
        repo.systemPromptFor(ItemType.WOODEN_TABLE)

        clock += TimeUnit.HOURS.toMillis(48)
        server.shutdown()

        // Stale beats the compiled-in default, and beats failing.
        assertEquals("MASTER V1\n\nITEM V1", repo.systemPromptFor(ItemType.WOODEN_TABLE))
    }

    @Test
    fun `a blank master response falls back rather than sending an empty system prompt`() = runTest {
        // Guards against someone accidentally emptying master.txt in the repo.
        server.enqueue(MockResponse().setBody("   "))
        server.enqueue(MockResponse().setBody(""))

        assertEquals(DefaultPrompts.MASTER.trimEnd(), repository().systemPromptFor(ItemType.OTHER))
    }

    @Test
    fun `clearCache forces a refetch`() = runTest {
        val repo = repository()
        server.enqueue(MockResponse().setBody("MASTER V1"))
        server.enqueue(MockResponse().setBody("ITEM V1"))
        repo.systemPromptFor(ItemType.WOODEN_TABLE)

        repo.clearCache()
        server.enqueue(MockResponse().setBody("MASTER V2"))
        server.enqueue(MockResponse().setBody("ITEM V2"))

        assertEquals("MASTER V2\n\nITEM V2", repo.systemPromptFor(ItemType.WOODEN_TABLE))
    }
}
