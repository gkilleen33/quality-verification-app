package com.qualityverifier.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * tools/app-versions.sh, which writes the app-versions.conf that tools/deploy.sh installs.
 *
 * Run against small throwaway git repositories, so each case can say exactly which
 * versionCode the tag holds and what the server has installed, and once against this
 * repository's own build files, so a change to how a build file states its versionCode
 * fails here rather than at a deploy.
 */
class DeployAppVersionsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val script = File("../tools/app-versions.sh").canonicalFile

    private data class Run(val exit: Int, val stdout: String, val stderr: String)

    private fun run(dir: File, vararg command: String): Run {
        val process = ProcessBuilder(*command).directory(dir).start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        assertTrue("timed out: ${command.joinToString(" ")}", process.waitFor(60, TimeUnit.SECONDS))
        return Run(process.exitValue(), stdout, stderr)
    }

    private fun git(repo: File, vararg args: String) {
        val result = run(
            repo, "git", "-c", "user.name=test", "-c", "user.email=test@example.invalid",
            "-c", "commit.gpgsign=false", "-c", "tag.gpgsign=false", *args,
        )
        assertEquals("git ${args.joinToString(" ")}: ${result.stderr}", 0, result.exit)
    }

    private fun buildFile(versionCode: Int) = """
        android {
            defaultConfig {
                minSdk = 24
                versionCode = $versionCode
                versionName = "0.0.$versionCode"
            }
        }
    """.trimIndent() + "\n"

    /** A repository whose tag v9.9.9 holds the given codes, with HEAD moved past it. */
    private fun repoTagged(kagua: Int, fundi: Int, tracker: Int, repoConf: String? = null): File {
        val repo = temp.newFolder("repo")
        git(repo, "init", "-q")
        fun write(path: String, text: String) =
            File(repo, path).apply { parentFile.mkdirs() }.writeText(text)
        write("app/build.gradle.kts", buildFile(kagua))
        write("fundi/build.gradle.kts", buildFile(fundi))
        write("tracker/build.gradle.kts", buildFile(tracker))
        if (repoConf != null) write("server/deploy/systemd/kagua.service.d/app-versions.conf", repoConf)
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "release")
        git(repo, "tag", "v9.9.9")
        // A later commit, so a script that read HEAD or the working tree would be caught.
        write("app/build.gradle.kts", buildFile(99))
        git(repo, "commit", "-q", "-am", "after the release")
        return repo
    }

    private fun appVersions(repo: File, rev: String, installed: File? = null): Run =
        run(repo, "bash", script.path, repo.path, rev, *listOfNotNull(installed?.path).toTypedArray())

    private fun settings(conf: String): Map<String, String> =
        Regex("""^Environment="([A-Z_]+)=(.*)"$""", RegexOption.MULTILINE)
            .findAll(conf).associate { it.groupValues[1] to it.groupValues[2] }

    @Test
    fun `latest is each app's versionCode at the tag, not at HEAD`() {
        val repo = repoTagged(kagua = 8, fundi = 8, tracker = 2)

        val result = appVersions(repo, "v9.9.9")

        assertEquals(result.stderr, 0, result.exit)
        val values = settings(result.stdout)
        assertEquals("8", values["KAGUA_APP_LATEST_KAGUA"])
        assertEquals("8", values["KAGUA_APP_LATEST_FUNDI"])
        assertEquals("2", values["KAGUA_APP_LATEST_TRACKER"])
        assertTrue(result.stdout.contains("[Service]"))
    }

    @Test
    fun `minimums and download links are kept from the installed file`() {
        val repo = repoTagged(
            kagua = 8, fundi = 8, tracker = 2,
            repoConf = "[Service]\nEnvironment=\"KAGUA_APP_MINIMUM_FUNDI=1\"\n",
        )
        val installed = temp.newFile("installed.conf").apply {
            writeText(
                """
                [Service]
                Environment="KAGUA_APP_LATEST_KAGUA=7"
                Environment="KAGUA_APP_MINIMUM_KAGUA=3"
                Environment="KAGUA_APP_LATEST_FUNDI=7"
                Environment="KAGUA_APP_MINIMUM_FUNDI=4"
                Environment="KAGUA_APP_DOWNLOAD_URL_FUNDI=https://example.invalid/fundi.apk"
                """.trimIndent() + "\n"
            )
        }

        val result = appVersions(repo, "v9.9.9", installed)

        assertEquals(result.stderr, 0, result.exit)
        val values = settings(result.stdout)
        assertEquals("8", values["KAGUA_APP_LATEST_KAGUA"])
        assertEquals("3", values["KAGUA_APP_MINIMUM_KAGUA"])
        assertEquals("4", values["KAGUA_APP_MINIMUM_FUNDI"])
        assertEquals("https://example.invalid/fundi.apk", values["KAGUA_APP_DOWNLOAD_URL_FUNDI"])
        // Not in the installed file and not in the repository's copy: the server's default.
        assertEquals("1", values["KAGUA_APP_MINIMUM_TRACKER"])
    }

    @Test
    fun `a minimum the server lacks comes from the repository's copy`() {
        val repo = repoTagged(
            kagua = 8, fundi = 8, tracker = 2,
            repoConf = "[Service]\nEnvironment=\"KAGUA_APP_MINIMUM_FUNDI=2\"\n",
        )
        val installed = temp.newFile("empty.conf")

        val values = settings(appVersions(repo, "v9.9.9", installed).stdout)

        assertEquals("2", values["KAGUA_APP_MINIMUM_FUNDI"])
        assertEquals("1", values["KAGUA_APP_MINIMUM_KAGUA"])
    }

    @Test
    fun `a build file without one versionCode line stops it, printing nothing`() {
        val repo = temp.newFolder("broken")
        git(repo, "init", "-q")
        File(repo, "app").mkdirs()
        File(repo, "app/build.gradle.kts").writeText(buildFile(8))
        File(repo, "fundi").mkdirs()
        File(repo, "fundi/build.gradle.kts").writeText("android { }\n")
        File(repo, "tracker").mkdirs()
        File(repo, "tracker/build.gradle.kts").writeText(buildFile(2))
        git(repo, "add", "-A")
        git(repo, "commit", "-q", "-m", "broken")

        val result = appVersions(repo, "HEAD")

        assertEquals(1, result.exit)
        assertEquals("", result.stdout)
        assertTrue(result.stderr, result.stderr.contains("fundi/build.gradle.kts"))
    }

    @Test
    fun `tools deploy sh still parses`() {
        // The deploy itself cannot run in a test (it needs AWS); this catches a quoting
        // mistake in the steps that call app-versions.sh before Nathan's next deploy does.
        val result = run(File(".."), "bash", "-n", File("../tools/deploy.sh").canonicalPath)
        assertEquals(result.stderr, 0, result.exit)
    }

    @Test
    fun `this repository's build files give the versionCode each one states`() {
        val root = File("..").canonicalFile
        val result = appVersions(root, "HEAD")

        assertEquals(result.stderr, 0, result.exit)
        val values = settings(result.stdout)
        val stated = Regex("""versionCode = (\d+)""")
        for ((app, path) in listOf("KAGUA" to "app", "FUNDI" to "fundi", "TRACKER" to "tracker")) {
            val atHead = run(root, "git", "show", "HEAD:$path/build.gradle.kts").stdout
            val code = stated.find(atHead)!!.groupValues[1]
            assertEquals(app, code, values["KAGUA_APP_LATEST_$app"])
        }
    }
}
