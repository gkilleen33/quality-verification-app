package com.qualityverifier.server.terms

import com.qualityverifier.domain.Audience
import com.qualityverifier.text.TermsDocument
import com.qualityverifier.text.TermsIndexEntry
import com.qualityverifier.text.sha256Hex

/** The languages a terms text is published in, mirrored by V28's `consents_language_known`. */
enum class TermsLanguage(val id: String) {
    ENGLISH("en"),
    KISWAHILI("sw");

    companion object {
        fun fromId(id: String?): TermsLanguage? = entries.firstOrNull { it.id == id }
    }
}

/** One published file: its exact bytes and their SHA-256. */
class TermsFile(val bytes: ByteArray, val sha256: String)

/**
 * The published terms texts, read once at start from the jar.
 *
 * The files live in the repository's `terms/` folder, which the server build copies into
 * the jar and the Fundi Bora build bundles into the APK, so there is one copy of each text.
 * `terms/index.txt` pins each file's SHA-256. [fromClasspath] refuses a file whose bytes
 * differ from its line, and the server then refuses to start, which the deploy's health
 * check reports. Serving a text that differs from the one the index promises would make
 * every agreement to it record a hash of words nobody was shown.
 */
class TermsTexts(private val files: Map<Key, TermsFile>) {

    data class Key(val audience: Audience, val version: String, val language: TermsLanguage)

    fun file(audience: Audience, version: String, language: TermsLanguage): TermsFile? =
        files[Key(audience, version, language)]

    /** The languages published for [audience] at [version], English first. */
    fun languages(audience: Audience, version: String): List<TermsLanguage> =
        TermsLanguage.entries.filter { Key(audience, version, it) in files }

    companion object {
        /** No texts at all: every app reads as having no terms. For tests and a server without the folder. */
        val NONE = TermsTexts(emptyMap())

        /**
         * Reads `terms/index.txt` and each file it lists from [loader]. Throws
         * [IllegalStateException] when the index is missing, a listed file is missing, a
         * file's hash differs from its line, or a file does not parse.
         */
        fun fromClasspath(loader: ClassLoader = TermsTexts::class.java.classLoader): TermsTexts {
            val index = loader.getResourceAsStream("terms/index.txt")?.use { it.readBytes() }
                ?: error("terms/index.txt is not in the jar; the server build copies the terms folder into it")
            return fromIndex(String(index, Charsets.UTF_8)) { path ->
                loader.getResourceAsStream("terms/$path")?.use { it.readBytes() }
            }
        }

        /** As [fromClasspath], reading each file with [read]. Separate so a test can supply the bytes. */
        fun fromIndex(index: String, read: (path: String) -> ByteArray?): TermsTexts {
            val files = TermsIndexEntry.parseIndex(index).associate { entry ->
                val audience = Audience.fromId(entry.app)
                    ?: error("terms/index.txt names an unknown app '${entry.app}'")
                val language = TermsLanguage.fromId(entry.language)
                    ?: error("terms/index.txt names an unknown language '${entry.language}'")
                val bytes = read(entry.path) ?: error("terms/${entry.path} is listed in the index but missing")
                val actual = sha256Hex(bytes)
                check(actual == entry.sha256) {
                    "terms/${entry.path} has SHA-256 $actual, but terms/index.txt pins ${entry.sha256}. " +
                        "A published text never changes; publish a new version instead."
                }
                // Parsed only to refuse a malformed file here rather than on a phone.
                runCatching { TermsDocument.parse(String(bytes, Charsets.UTF_8)) }
                    .onFailure { throw IllegalStateException("terms/${entry.path} does not parse: ${it.message}", it) }
                Key(audience, entry.version, language) to TermsFile(bytes, actual)
            }
            return TermsTexts(files)
        }
    }
}
