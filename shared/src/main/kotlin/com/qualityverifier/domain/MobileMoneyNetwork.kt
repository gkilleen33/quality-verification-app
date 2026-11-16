package com.qualityverifier.domain

/**
 * The mobile money providers PesaLog reads (V33), and the one list of them.
 *
 * A provider is here only when MobileMoneyParser has rules for it that were written against
 * real messages: M-Pesa, and Airtel Money from the 2026 study-phone samples. Adding a third
 * is one entry here, its sender ids and rules in the parser, and a migration that replaces
 * `tracker_transactions_network_known` and `tracker_unread_counts_network_known`;
 * `TrackerVocabularyTest` fails until all three agree.
 *
 * [id] is the parser's network name (MobileMoneyParser.NETWORK_*), which is also what the
 * server stores. [displayName] is what the phone shows.
 */
enum class MobileMoneyNetwork(val id: String, val displayName: String) {
    MPESA("mpesa", "M-Pesa"),
    AIRTEL("airtel", "Airtel Money");

    companion object {
        fun fromId(id: String?): MobileMoneyNetwork? = entries.firstOrNull { it.id == id }
    }
}
