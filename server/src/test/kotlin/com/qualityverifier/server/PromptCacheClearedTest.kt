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
 * What tools/deploy.sh relies on when it dates every file in /var/lib/kagua/prompts to 1970
 * before the restart (`touch -d @0`): the next assessment fetches its prompt from GitHub
 * instead of serving the copy cached up to a day earlier, and if GitHub cannot be reached
 * it still serves that copy rather than the older one compiled into the jar. Built the way
 * Application.main builds the repository, with the cache in a "prompts" folder under the
 * data directory (KAGUA_DATA_DIR, /var/lib/kagua on the server).
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

    private suspend fun load(dataDirectory: File) =
        repository(dataDirectory).systemPromptFor(ItemType.UPHOLSTERED_SOFA, Audience.FUNDI)

    /** The deploy's step, from outside the process: every cached file dated 1970. */
    private fun markOutOfDate(dataDirectory: File) {
        File(dataDirectory, "prompts").listFiles()!!.forEach { it.setLastModified(0) }
    }

    @Test
    fun `the deploy path matches where the server keeps its prompts`() {
        val deployScript = File("../tools/deploy.sh").readText()
        val config = Config.fromEnvironment { null }
        assertEquals(
            "PROMPT_CACHE=${File(config.dataDirectory, "prompts").path}",
            Regex("""^PROMPT_CACHE=.*$""", RegexOption.MULTILINE).find(deployScript)!!.value,
        )
    }

    @Test
    fun `a cache marked out of date makes the next load fetch the prompt again`() = runTest {
        val dataDirectory = temp.newFolder("kagua")
        github.enqueue(MockResponse().setBody("FUNDI MASTER, OLD"))
        github.enqueue(MockResponse().setBody("SOFA, OLD"))
        assertEquals("FUNDI MASTER, OLD\n\nSOFA, OLD", load(dataDirectory))

        // Within the day the cached copy is served and GitHub is not asked: the delay that
        // left prompt E a day late on 18 November 2026.
        assertEquals("FUNDI MASTER, OLD\n\nSOFA, OLD", load(dataDirectory))
        assertEquals(2, github.requestCount)

        markOutOfDate(dataDirectory)
        github.enqueue(MockResponse().setBody("FUNDI MASTER, NEW"))
        github.enqueue(MockResponse().setBody("SOFA, NEW"))

        assertEquals("FUNDI MASTER, NEW\n\nSOFA, NEW", load(dataDirectory))
        assertEquals(4, github.requestCount)
    }

    @Test
    fun `with GitHub unreachable after the deploy the cached copy is still served`() = runTest {
        val dataDirectory = temp.newFolder("kagua")
        github.enqueue(MockResponse().setBody("FUNDI MASTER, CACHED"))
        github.enqueue(MockResponse().setBody("SOFA, CACHED"))
        load(dataDirectory)

        markOutOfDate(dataDirectory)
        github.enqueue(MockResponse().setResponseCode(503))
        github.enqueue(MockResponse().setResponseCode(503))

        // Not DefaultPrompts, which is only as new as the jar's last regeneration.
        assertEquals("FUNDI MASTER, CACHED\n\nSOFA, CACHED", load(dataDirectory))
    }
}
