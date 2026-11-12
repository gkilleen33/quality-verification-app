package com.qualityverifier.text

/**
 * The two M-Pesa questions Fundi Bora asks, word for word, and the hash each agreement
 * records (V29).
 *
 * Grady's two questions (Nathan, 11 November 2026): first, may the app read M-Pesa
 * messages and keep them on the phone; second, only if yes, may it send them to the
 * server. Each is its own agreement, withdrawn on its own from Settings.
 *
 * One copy for the phone and the server. The phone shows [textFor] and sends its SHA-256
 * with the agreement; the server checks the hash against [published] before recording
 * it, as it checks the terms against terms/index.txt. So a consent row names the exact
 * words agreed to, and a change of wording is a new [VERSION] with the old one kept in
 * [published], or an older app's agreements would be refused.
 *
 * English only, as the rest of Fundi Bora is (see [TermsLabels]).
 */
object MpesaConsentText {

    /** The label stored in `consents.version` for both questions. */
    const val VERSION = "2026-11"

    /** The language stored in `consents.language`. */
    const val LANGUAGE = "en"

    /** Question 1: reading. Nathan's decisions of 11 November 2026 on which messages. */
    val READ: String = listOf(
        "Fundi Bora can read your M-Pesa messages and show the money coming into and going out " +
            "of your business, in a Finances tab.",
        "• It reads only messages from M-Pesa. It never reads any other messages.\n" +
            "• It reads only M-Pesa messages that arrive after you agree. It never reads older ones.\n" +
            "• For each payment it keeps the amount, the time, the type, the fee, your balance " +
            "after it, and who it was with, on this phone.\n" +
            "• Nothing is sent from your phone unless you also agree to the next question.",
        "You can turn this off at any time in Settings. Fundi Bora then stops reading your messages.",
        "May Fundi Bora read your M-Pesa messages and keep these details on this phone?",
    ).joinToString("\n\n")

    /** Question 2: sending. Asked only after a yes to [READ]. */
    val SEND: String = listOf(
        "Fundi Bora can send your M-Pesa payments to its server, in the United States as section " +
            "14 of the terms describes.",
        "• What is sent: the amount, time, type and fee of each payment, your balance after it, " +
            "who it was with as a code, and the categories, pieces and comments you add.\n" +
            "• Who it was with: the other person's phone number or name is turned into a code on " +
            "the server. The number or name itself is not kept.\n" +
            "• What it is for: the app's features and research. It is not used for any " +
            "commercial purpose.",
        "You can turn this off at any time in Settings. Fundi Bora then stops sending, and your " +
            "payments stay on this phone.",
        "May Fundi Bora send these details to its server?",
    ).joinToString("\n\n")

    /** The text of question [kind] (`mpesa_read` or `mpesa_send`), or null for any other kind. */
    fun textFor(kind: String): String? = when (kind) {
        READ_KIND -> READ
        SEND_KIND -> SEND
        else -> null
    }

    /** SHA-256 of [text] as UTF-8, lower-case hex: what an agreement sends and records. */
    fun sha256Of(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * Every (kind, version, language) a phone may have shown, with its hash. A new wording
     * adds a row here rather than replacing one, so an older app's agreement still checks.
     */
    val published: Map<Triple<String, String, String>, String> = mapOf(
        Triple(READ_KIND, VERSION, LANGUAGE) to sha256Of(READ),
        Triple(SEND_KIND, VERSION, LANGUAGE) to sha256Of(SEND),
    )

    const val READ_KIND = "mpesa_read"
    const val SEND_KIND = "mpesa_send"
}
