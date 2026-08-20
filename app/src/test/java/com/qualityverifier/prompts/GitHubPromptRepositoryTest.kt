package com.qualityverifier.prompts

import com.qualityverifier.data.prompts.DefaultPrompts
import com.qualityverifier.data.prompts.GitHubPromptRepository
import com.qualityverifier.data.prompts.PromptCache
import com.qualityverifier.data.prompts.assembleSystemPrompt
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
    fun `a missing item file falls back to the compiled-in checklist`() = runTest {
        server.enqueue(MockResponse().setBody("MASTER TEXT"))
        server.enqueue(MockResponse().setResponseCode(404))

        val prompt = repository().systemPromptFor(ItemType.WOODEN_BED)

        assertEquals(
            assembleSystemPrompt("MASTER TEXT", DefaultPrompts.forItem(ItemType.WOODEN_BED)),
            prompt,
        )
        assertTrue(prompt.contains("centre rail"))
    }

    @Test
    fun `every item type has an offline checklist`() = runTest {
        // All six item prompts are populated, so no category should ever fall back to
        // the master prompt alone - that would silently drop the guided walkthrough.
        server.shutdown()
        val repo = repository()

        ItemType.entries.forEach { itemType ->
            val prompt = repo.systemPromptFor(itemType)
            assertTrue(
                "${itemType.id} lost the master prompt",
                prompt.contains("furniture quality verification assistant"),
            )
            assertTrue(
                "${itemType.id} has no walkthrough",
                prompt.contains("walk you through"),
            )
            assertTrue(
                "${itemType.id} does not step through one item at a time",
                prompt.contains("one step at a time"),
            )
            assertTrue(
                "${itemType.id} should not offer example photos it cannot send",
                !prompt.contains("(example photo)"),
            )
        }
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
    fun `offline with no cache still yields the table walkthrough checklist`() = runTest {
        // The whole point of the compiled-in item fallback: a fresh install with no
        // connectivity must still run the checklist, not fall back to master alone.
        server.shutdown()

        val prompt = repository().systemPromptFor(ItemType.WOODEN_TABLE)

        assertTrue(prompt.startsWith(DefaultPrompts.MASTER.trimEnd()))
        assertTrue(prompt.contains("I am going to walk you through assessing the table"))
        assertTrue(prompt.contains("one step at a time"))
        assertEquals(
            assembleSystemPrompt(DefaultPrompts.MASTER, DefaultPrompts.forItem(ItemType.WOODEN_TABLE)),
            prompt,
        )
    }

    @Test
    fun `a fetched item prompt overrides the compiled-in copy`() = runTest {
        // Remote stays the source of truth, so prompts can be changed without a release.
        server.enqueue(MockResponse().setBody("MASTER FROM REPO"))
        server.enqueue(MockResponse().setBody("TABLE CHECKLIST V2"))

        assertEquals(
            "MASTER FROM REPO\n\nTABLE CHECKLIST V2",
            repository().systemPromptFor(ItemType.WOODEN_TABLE),
        )
    }

    @Test
    fun `an item file emptied in the repo beats the compiled-in copy`() = runTest {
        // Deliberate: clearing a prompt file upstream must actually clear it on devices.
        server.enqueue(MockResponse().setBody("MASTER FROM REPO"))
        server.enqueue(MockResponse().setBody(""))

        assertEquals("MASTER FROM REPO", repository().systemPromptFor(ItemType.WOODEN_TABLE))
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
