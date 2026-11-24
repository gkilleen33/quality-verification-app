package com.qualityverifier.text

/**
 * PesaLog's two questions, word for word, the hash each agreement records (V33), and the
 * version of PesaLog's own terms.
 *
 * The same shape as Fundi Bora's M-Pesa questions (MpesaConsentText): first, may the app
 * read mobile money notifications and keep the payments on the phone; second, only if yes,
 * may it send them to the research team. Each is its own agreement, withdrawn on its own from
 * Settings. The phone shows [textFor] and sends its SHA-256; the server checks the hash
 * against [published] before recording it. A change of wording is a new [VERSION], with the
 * old one kept in [published], or an older app's agreements would be refused.
 *
 * The wording follows sections 2, 3, 5, 6 and 8 of PesaLog's terms (terms/tracker/), and
 * names the limit of reading notifications rather than the inbox: nothing from before the
 * install, and nothing the person dismissed while the app was off.
 *
 * English only. No Kiswahili has been drafted; a translation needs a native speaker's review
 * and a new [published] row in `sw`.
 */
object TrackerConsentText {

    /**
     * The version of PesaLog's terms an account must have agreed to: the folder under
     * `terms/tracker/`. Not a setting, unlike Fundi Bora's terms_version (V27): settings are
     * per audience, and PesaLog shares Fundi Bora's. A new text is a new folder, a line in
     * terms/index.txt, and this constant raised, in one PR.
     *
     * 2026-11-24: the lists of what PesaLog keeps and sends carry the two sentences of the
     * questions' new wording (row 33).
     */
    const val TERMS_VERSION = "2026-11-24"

    /**
     * The label stored in `consents.version` for both questions.
     *
     * 2026-11-24 (row 33; Nathan, 17 November 2026): question 1 says why the phone keeps the
     * other person's name and number, and question 2 says the server replaces them with codes
     * made from the name, the number and part of the number (V42), and never stores them.
     */
    const val VERSION = "2026-11-24"

    /** The language stored in `consents.language`. */
    const val LANGUAGE = "en"

    /** Question 1: reading. */
    val READ: String = listOf(
        "PesaLog can read the notifications your phone shows when an M-Pesa or Airtel Money " +
            "message arrives, and keep a log of your payments on this phone.",
        "• Android's notification access lets an app see every notification on this phone. " +
            "PesaLog ignores every notification except mobile money messages from M-Pesa and " +
            "Airtel Money. It does not store or send any other notification.\n" +
            "• It reads only messages that arrive after you agree. It cannot read messages that " +
            "arrived before you installed it, or notifications you dismissed while it was off. " +
            "It never reads your SMS inbox.\n" +
            "• For each payment it keeps the amount, the time, the type, the fee, your balance " +
            "after it, the transaction code, the network, and the name or number of the other " +
            "person, on this phone. PesaLog keeps the name and number only to show you who each " +
            "payment was with, so you can follow how your business is doing.\n" +
            "• Nothing is sent from your phone unless you also agree to the next question.",
        "You can turn this off at any time in Settings, or by turning off notification access " +
            "in your phone's settings. PesaLog then stops reading.",
        "May PesaLog read your mobile money notifications and keep these details on this phone?",
    ).joinToString("\n\n")

    /** Question 2: sending. Asked only after a yes to [READ]. */
    val SEND: String = listOf(
        "PesaLog can send your payments to the research team at the Development Innovation " +
            "Lab (DIL), University of Chicago, on a server in the United States, as section 10 " +
            "of the terms describes.",
        "• What is sent: the amount, time, type, fee and code of each payment, your balance " +
            "after it, the network, who it was with as a code, the categories and comments you " +
            "add, and for each day the number of mobile money messages PesaLog could not read.\n" +
            "• Who it was with: PesaLog sends the other person's name or number to the server " +
            "so that the server can replace them with secure codes, made from the name, the " +
            "number, and part of the number. The server never stores the real name or phone " +
            "number.\n" +
            "• What it is for: academic research only. DIL does not sell it, use it for " +
            "advertising or to decide on credit, or send it to an artificial-intelligence model.",
        "You can turn this off at any time in Settings. PesaLog then stops sending, and your " +
            "payments stay on this phone. You can also delete every record PesaLog sent from " +
            "the server, in Settings.",
        "May PesaLog send these details to the research team?",
    ).joinToString("\n\n")

    /** The text of question [kind] (`tracker_read` or `tracker_send`), or null for any other kind. */
    fun textFor(kind: String): String? = when (kind) {
        READ_KIND -> READ
        SEND_KIND -> SEND
        else -> null
    }

    /** SHA-256 of [text] as UTF-8, lower-case hex: what an agreement sends and records. */
    fun sha256Of(text: String): String = sha256Hex(text.toByteArray(Charsets.UTF_8))

    /**
     * The 2026-11 wording, which PesaLog 0.1.0 shows and whose agreements must still be
     * recorded: question 1 without the sentence on why the name and number are kept, and
     * question 2 saying the server turns the number or name into one code. Word for word, so
     * its hash is the one 0.1.0 sends; TrackerConsentTextTest pins both hashes.
     */
    private val READ_2026_11: String = listOf(
        "PesaLog can read the notifications your phone shows when an M-Pesa or Airtel Money " +
            "message arrives, and keep a log of your payments on this phone.",
        "• Android's notification access lets an app see every notification on this phone. " +
            "PesaLog ignores every notification except mobile money messages from M-Pesa and " +
            "Airtel Money. It does not store or send any other notification.\n" +
            "• It reads only messages that arrive after you agree. It cannot read messages that " +
            "arrived before you installed it, or notifications you dismissed while it was off. " +
            "It never reads your SMS inbox.\n" +
            "• For each payment it keeps the amount, the time, the type, the fee, your balance " +
            "after it, the transaction code, the network, and the name or number of the other " +
            "person, on this phone.\n" +
            "• Nothing is sent from your phone unless you also agree to the next question.",
        "You can turn this off at any time in Settings, or by turning off notification access " +
            "in your phone's settings. PesaLog then stops reading.",
        "May PesaLog read your mobile money notifications and keep these details on this phone?",
    ).joinToString("\n\n")

    private val SEND_2026_11: String = listOf(
        "PesaLog can send your payments to the research team at the Development Innovation " +
            "Lab (DIL), University of Chicago, on a server in the United States, as section 10 " +
            "of the terms describes.",
        "• What is sent: the amount, time, type, fee and code of each payment, your balance " +
            "after it, the network, who it was with as a code, the categories and comments you " +
            "add, and for each day the number of mobile money messages PesaLog could not read.\n" +
            "• Who it was with: the server turns the other person's phone number or name into a " +
            "code. The number or name itself is not kept.\n" +
            "• What it is for: academic research only. DIL does not sell it, use it for " +
            "advertising or to decide on credit, or send it to an artificial-intelligence model.",
        "You can turn this off at any time in Settings. PesaLog then stops sending, and your " +
            "payments stay on this phone. You can also delete every record PesaLog sent from " +
            "the server, in Settings.",
        "May PesaLog send these details to the research team?",
    ).joinToString("\n\n")

    /** The version label PesaLog 0.1.0 records with each question. */
    const val VERSION_2026_11 = "2026-11"

    /** Every (kind, version, language) a phone may have shown, with its hash. */
    val published: Map<Triple<String, String, String>, String> = mapOf(
        Triple(READ_KIND, VERSION_2026_11, LANGUAGE) to sha256Of(READ_2026_11),
        Triple(SEND_KIND, VERSION_2026_11, LANGUAGE) to sha256Of(SEND_2026_11),
        Triple(READ_KIND, VERSION, LANGUAGE) to sha256Of(READ),
        Triple(SEND_KIND, VERSION, LANGUAGE) to sha256Of(SEND),
    )

    /** The two boxes on PesaLog's terms screen (V33), agreed together. */
    const val TERMS_KIND = "tracker_terms"
    const val TRANSFER_KIND = "tracker_transfer"

    const val READ_KIND = "tracker_read"
    const val SEND_KIND = "tracker_send"
}
