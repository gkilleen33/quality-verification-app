package com.qualityverifier.server

import com.qualityverifier.domain.Audience
import com.qualityverifier.server.terms.TermsLanguage
import com.qualityverifier.server.terms.TermsTexts
import com.qualityverifier.text.TermsDocument
import com.qualityverifier.text.TermsIndexEntry
import com.qualityverifier.text.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The published terms texts in the repository's `terms/` folder.
 *
 * Every agreement records the SHA-256 of the file shown, and `terms/index.txt` pins each
 * file's hash. Editing a published file fails here, before it can reach a phone: a change
 * to the words is a new version.
 */
class TermsTextTest {

    private val folder = File("../terms")

    private val index: List<TermsIndexEntry> by lazy {
        val file = File(folder, "index.txt")
        assertTrue("expected the terms index at ${file.absolutePath}; the module root moved?", file.exists())
        TermsIndexEntry.parseIndex(file.readText())
    }

    @Test
    fun `every listed file exists and matches its pinned hash`() {
        assertTrue("the index lists no texts", index.isNotEmpty())
        index.forEach { entry ->
            val file = File(folder, entry.path)
            assertTrue("terms/${entry.path} is listed but missing", file.exists())
            assertEquals(
                "terms/${entry.path} differs from its pinned hash. A published text never " +
                    "changes; publish a new version instead",
                entry.sha256,
                sha256Hex(file.readBytes()),
            )
        }
    }

    @Test
    fun `every file in the folder is listed`() {
        val listed = index.map { it.path }.toSet()
        val present = folder.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".md") }
            .map { it.relativeTo(folder).invariantSeparatorsPath }
            .toSet()
        assertEquals("a text in terms/ that the index does not list is never served", listed, present)
    }

    @Test
    fun `every listed file parses, and a file in another language carries its own labels`() {
        index.forEach { entry ->
            val doc = TermsDocument.parse(File(folder, entry.path).readText())
            assertTrue("terms/${entry.path} has no body", doc.blocks.isNotEmpty())
            if (entry.language != TermsLanguage.ENGLISH.id) {
                // English labels are the app's own; any other language must bring its own,
                // or its screen would show English boxes under a translated text.
                assertNotNull("terms/${entry.path} has no terms_label", doc.termsLabel)
                assertNotNull("terms/${entry.path} has no transfer_label", doc.transferLabel)
            }
        }
    }

    @Test
    fun `the English text is sections 1 to 15 and nothing after`() {
        val text = File(folder, "fundi/2026-11/en.md").readText()
        assertTrue(text.startsWith("# Fundi Bora: Terms of Use and Privacy Notice\n"))
        assertTrue(text.contains("\nVersion: 2026-11\n"))
        (1..15).forEach { section ->
            assertTrue("section $section is missing", Regex("""(?m)^## $section\. """).containsMatchIn(text))
        }
        assertTrue("the drafting note after the final rule was copied", !text.contains("Checkboxes below the text"))
        assertTrue("the checkbox labels belong to the app, not the text", !text.contains("- [ ]"))
    }

    @Test
    fun `the terms version V27 seeds has an English text`() {
        val seeded = Regex("""\('fundi',\s*'terms_version',\s*'([^']+)'\)""")
            .find(File("db/migrations/V27__app_settings.sql").readText())
            ?.groupValues?.get(1)
        assertNotNull("V27 seeds no terms_version for fundi", seeded)
        assertTrue(
            "V27 seeds terms_version $seeded, which has no English text",
            index.any { it.app == Audience.FUNDI.id && it.version == seeded && it.language == "en" },
        )
    }

    @Test
    fun `the server reads the folder as the jar holds it`() {
        val texts = TermsTexts.fromIndex(File(folder, "index.txt").readText()) { path ->
            File(folder, path).takeIf { it.exists() }?.readBytes()
        }
        assertNotNull(texts.file(Audience.FUNDI, "2026-11", TermsLanguage.ENGLISH))
    }

    @Test
    fun `a file that differs from its pinned hash stops the server`() {
        assertThrows(IllegalStateException::class.java) {
            TermsTexts.fromIndex("fundi 2026-11 en ${"0".repeat(64)}") { "changed".toByteArray() }
        }
    }

    @Test
    fun `a listed file that is missing stops the server`() {
        assertThrows(IllegalStateException::class.java) {
            TermsTexts.fromIndex("fundi 2026-11 en ${"0".repeat(64)}") { null }
        }
    }
}
