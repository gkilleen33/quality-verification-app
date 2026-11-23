package com.qualityverifier.server

import com.qualityverifier.data.prompts.GitHubPromptRepository
import com.qualityverifier.data.prompts.PromptCache
import com.qualityverifier.domain.Audience
import com.qualityverifier.domain.ItemType
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * What tools/deploy.sh relies on when it runs `rm -rf /var/lib/kagua/prompts` before the
 * restart: with the folder gone, the next assessment fetches its prompt from GitHub instead
 * of serving the copy cached up to a day earlier. Built the way Application.main builds the
 * repository, with the cache in a "prompts" folder under the data directory.
 */
class PromptCacheClearedTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var github: MockWebServer

    @Before
    fun setUp() {
        github = MockWebServer()
        github.start()
    }

    @After
    fun tearDown() {
        github.shutdown()
    }

    private fun repository(dataDirectory: File) = GitHubPromptRepository(
        client = OkHttpClient.Builder().readTimeout(2, TimeUnit.SECONDS).build(),
        cache = PromptCache(File(dataDirectory, "prompts")),
        baseUrl = github.url("/prompts/").toString(),
    )

    @Test
    fun `a deleted prompts folder makes the next load fetch the prompt again`() = runTest {
        val dataDirectory = temp.newFolder("kagua")
        github.enqueue(MockResponse().setBody("FUNDI MASTER, OLD"))
        github.enqueue(MockResponse().setBody("SOFA, OLD"))
        assertEquals(
            "FUNDI MASTER, OLD\n\nSOFA, OLD",
            repository(dataDirectory).systemPromptFor(ItemType.UPHOLSTERED_SOFA, Audience.FUNDI),
        )

        // Within the day the cached copy is served and GitHub is not asked: the delay that
        // left prompt E a day late on 18 November 2026.
        assertEquals(
            "FUNDI MASTER, OLD\n\nSOFA, OLD",
            repository(dataDirectory).systemPromptFor(ItemType.UPHOLSTERED_SOFA, Audience.FUNDI),
        )
        assertEquals(2, github.requestCount)

        // The deploy's step, from outside the process, then a new process as the restart gives.
        File(dataDirectory, "prompts").deleteRecursively()
        github.enqueue(MockResponse().setBody("FUNDI MASTER, NEW"))
        github.enqueue(MockResponse().setBody("SOFA, NEW"))

        assertEquals(
            "FUNDI MASTER, NEW\n\nSOFA, NEW",
            repository(dataDirectory).systemPromptFor(ItemType.UPHOLSTERED_SOFA, Audience.FUNDI),
        )
        assertEquals(4, github.requestCount)
    }
}
