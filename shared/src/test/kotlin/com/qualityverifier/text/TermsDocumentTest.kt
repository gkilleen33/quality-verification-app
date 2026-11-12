package com.qualityverifier.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TermsDocumentTest {

    @Test
    fun `a file without front matter is all body`() {
        val doc = TermsDocument.parse("# Terms\n\n## 1. About\n\nText.\n")

        assertTrue(doc.fields.isEmpty())
        assertNull(doc.termsLabel)
        assertEquals("# Terms\n\n## 1. About\n\nText.\n", doc.body)
        assertEquals(3, doc.blocks.size)
    }

    @Test
    fun `front matter carries a language's own labels`() {
        val doc = TermsDocument.parse(
            """
            ---
            title: Masharti
            terms_label: Ninakubali masharti.
            transfer_label: Ninakubali kutuma taarifa zangu.
            hint: Sogeza hadi mwisho.
            language_name: Kiswahili
            ---

            # Masharti
            """.trimIndent()
        )

        assertEquals("Masharti", doc.title)
        assertEquals("Ninakubali masharti.", doc.termsLabel)
        assertEquals("Ninakubali kutuma taarifa zangu.", doc.transferLabel)
        assertEquals("Sogeza hadi mwisho.", doc.hint)
        assertEquals("# Masharti", doc.body)
    }

    @Test
    fun `a value may hold a colon`() {
        val doc = TermsDocument.parse("---\ntitle: Terms: Fundi Bora\n---\nBody")
        assertEquals("Terms: Fundi Bora", doc.title)
    }

    @Test
    fun `front matter that is never closed is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermsDocument.parse("---\ntitle: Terms\n# Body")
        }
    }

    @Test
    fun `an unknown front matter key is refused`() {
        // A typo such as "trasfer_label" would otherwise leave the English label showing
        // above a Kiswahili text.
        assertThrows(IllegalArgumentException::class.java) {
            TermsDocument.parse("---\ntrasfer_label: x\n---\nBody")
        }
    }

    @Test
    fun `the index reads four fields per line and skips comments`() {
        val sha = "a".repeat(64)
        val entries = TermsIndexEntry.parseIndex("# app version language sha256\n\nfundi 2026-11 en $sha\n")

        assertEquals(listOf(TermsIndexEntry("fundi", "2026-11", "en", sha)), entries)
        assertEquals("fundi/2026-11/en.md", entries.single().path)
    }

    @Test
    fun `an index line without a lowercase hash is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            TermsIndexEntry.parseIndex("fundi 2026-11 en ${"A".repeat(64)}")
        }
        assertThrows(IllegalArgumentException::class.java) {
            TermsIndexEntry.parseIndex("fundi 2026-11 en")
        }
    }

    @Test
    fun `the same text listed twice is refused`() {
        val sha = "b".repeat(64)
        assertThrows(IllegalArgumentException::class.java) {
            TermsIndexEntry.parseIndex("fundi 2026-11 en $sha\nfundi 2026-11 en $sha")
        }
    }

    @Test
    fun `sha256Hex matches a known digest`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }
}
