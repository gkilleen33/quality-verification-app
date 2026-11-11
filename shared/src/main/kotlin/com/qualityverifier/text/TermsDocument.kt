package com.qualityverifier.text

import java.security.MessageDigest

/**
 * One published terms text, as a file in the repository's `terms/` folder.
 *
 * The body is Markdown, shown on the terms screen block by block. An optional front matter
 * block, between two `---` lines at the very top, carries the screen's own words for a
 * language the app does not ship: the title, the two checkbox labels and the hint. The
 * English file has none; its labels are the app's (see FundiLabels), so English needs no
 * front matter. A Kiswahili file carries them, so adding Kiswahili needs that one file and
 * no new APK.
 *
 * Here rather than in either app so the server's tests check every published file with the
 * same parser the phone uses.
 */
data class TermsDocument(
    /** The front matter, key to value. Empty for a file without it. */
    val fields: Map<String, String>,
    /** The Markdown shown as the terms. */
    val body: String,
) {
    val blocks: List<MdBlock> get() = parseMarkdown(body)

    val title: String? get() = fields[TITLE]
    val termsLabel: String? get() = fields[TERMS_LABEL]
    val transferLabel: String? get() = fields[TRANSFER_LABEL]
    val hint: String? get() = fields[HINT]

    companion object {
        const val TITLE = "title"
        const val TERMS_LABEL = "terms_label"
        const val TRANSFER_LABEL = "transfer_label"
        const val HINT = "hint"
        const val LANGUAGE_NAME = "language_name"

        /** The keys a front matter block may hold. Anything else is a typo, refused by [parse]. */
        val KNOWN_FIELDS = setOf(TITLE, TERMS_LABEL, TRANSFER_LABEL, HINT, LANGUAGE_NAME)

        private const val FENCE = "---"

        /**
         * Splits [text] into front matter and body. Throws [IllegalArgumentException] for a
         * front matter block that is never closed, has a line without a colon, or names a
         * key outside [KNOWN_FIELDS], so a malformed file fails CI rather than a phone.
         */
        fun parse(text: String): TermsDocument {
            val normalised = text.replace("\r\n", "\n")
            val lines = normalised.split('\n')
            if (lines.firstOrNull()?.trim() != FENCE) return TermsDocument(emptyMap(), normalised)

            val close = lines.drop(1).indexOfFirst { it.trim() == FENCE }
            require(close >= 0) { "front matter is never closed with ---" }
            val fields = lines.subList(1, close + 1).filter { it.isNotBlank() }.associate { line ->
                val colon = line.indexOf(':')
                require(colon > 0) { "front matter line has no key: '$line'" }
                val key = line.substring(0, colon).trim()
                require(key in KNOWN_FIELDS) { "front matter key '$key' is not one of $KNOWN_FIELDS" }
                key to line.substring(colon + 1).trim()
            }
            val body = lines.drop(close + 2).joinToString("\n").trimStart('\n')
            return TermsDocument(fields, body)
        }
    }
}

/**
 * One line of `terms/index.txt`: which file is published for an app, version and language,
 * and the SHA-256 of its exact bytes.
 *
 * The index pins each published text. A file whose bytes no longer match its line is a
 * published text that changed under an existing version label, which fails the server's
 * start and its tests. A change to the words is a new version, not an edit.
 */
data class TermsIndexEntry(
    /** The audience id: `fundi` or `buyer`. */
    val app: String,
    val version: String,
    /** `en` or `sw`. */
    val language: String,
    val sha256: String,
) {
    /** Where the file sits under `terms/`. */
    val path: String get() = "$app/$version/$language.md"

    companion object {
        private val SHA = Regex("^[0-9a-f]{64}$")

        /**
         * Reads the index. Blank lines and lines starting `#` are skipped. Throws
         * [IllegalArgumentException] for a line that is not four fields with a lowercase
         * hex hash, or for the same app, version and language listed twice.
         */
        fun parseIndex(text: String): List<TermsIndexEntry> {
            val entries = text.replace("\r\n", "\n").split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { line ->
                    val parts = line.split(Regex("""\s+"""))
                    require(parts.size == 4) { "index line is not 'app version language sha256': '$line'" }
                    require(SHA.matches(parts[3])) { "index line has no lowercase SHA-256: '$line'" }
                    TermsIndexEntry(parts[0], parts[1], parts[2], parts[3])
                }
            val repeated = entries.groupBy { Triple(it.app, it.version, it.language) }.filterValues { it.size > 1 }
            require(repeated.isEmpty()) { "index lists these more than once: ${repeated.keys}" }
            return entries
        }
    }
}

/** Lowercase hex SHA-256 of [bytes]: the fingerprint each agreement records. */
fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
