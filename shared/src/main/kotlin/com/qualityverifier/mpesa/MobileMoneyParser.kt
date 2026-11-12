// Ported from Drew Burd's money-tracking-kenya (https://github.com/drewburd4/money-tracking-kenya),
// commit 5355a31, android/app/src/main/java/com/utafiti/sms/MobileMoneyParser.kt. Changed only
// in its package. Fundi Bora reads M-Pesa messages through it (MpesaReader); its Airtel Money
// rules and RuleSet come along unchanged so that the ported tests run as written, and nothing
// here fetches rules from anywhere. Used under the MIT licence below, which the licence
// requires to travel with the file.
//
// MIT License
//
// Copyright (c) 2026 Drew Burd
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package com.qualityverifier.mpesa

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One parsed mobile money confirmation.
 *
 * Uploaded: [network], [type], [direction], [amount], [cost], [counterpartyHash],
 * [counterpartyKind], [counterpartyNetwork], [ref], [occurredAt], [messageHash], [parseStatus],
 * and [body] while the message is not fully recognised (so the rule can be fixed centrally).
 * The counterparty name and phone number stay on the phone.
 */
data class Transaction(
    val network: String,
    val type: String,
    val direction: String,
    val amount: Double?,
    val cost: Double?,
    val counterparty: String?,
    val counterpartyPhone: String?,
    val counterpartyHash: String?,
    val counterpartyKind: String,
    val counterpartyNetwork: String,
    val ref: String?,
    val reversedRef: String?,
    val bodyTime: Long?,
    val occurredAt: Long,
    val messageHash: String,
    val parseStatus: String,
    val sender: String,
    val body: String,
    /** Fuliza repayments only: true when the text says it fully paid the outstanding Fuliza. */
    val fulizaCleared: Boolean? = null,
    /** The outstanding Fuliza amount the text prints. Stays on the phone; see FulizaCharges. */
    val fulizaOutstanding: Double? = null,
    /**
     * A second hash of the same counterparty, keyed on the number reduced to what M-Pesa shows
     * on money in (first four digits, last three). A send carries the full number and a receipt
     * only the masked one, so this is what matches the two sides of one relationship.
     */
    val counterpartyLinkHash: String? = null,
    /**
     * The wallet balance the text prints after the transaction (1.34+): "New M-PESA balance is
     * Ksh1,234.56", Airtel's "Bal: Ksh 94.0". Stored on the phone for every text; uploaded only
     * for an enrolment that consented to balances (the 1.34+ wizard) or where the desk set
     * upload_balances for the device.
     */
    val balanceAfter: Double? = null,
)

/**
 * One recognition rule: a regex plus how to read the amount and the counterparty out of its
 * groups. Built-in rules live in [MobileMoneyParser]; server rules arrive through
 * [RuleSet.fromRows] and are tried first.
 */
data class ParserRule(
    val network: String,
    val priority: Int,
    val type: String,
    val direction: String,
    val kind: String,
    val regex: Regex,
    val amountGroups: List<Int> = emptyList(),
    val partyGroups: List<Int> = emptyList(),
    val defaultParty: String? = null,
    val amountFromText: Boolean = false,
    val status: String = MobileMoneyParser.STATUS_OK,
    val id: Long = 0L,
)

/** Server-provided rules: anchored sender-id rules plus recognition rules, sorted by (priority, id). */
class RuleSet(
    val senders: List<Pair<Regex, String>>,
    val rules: List<ParserRule>,
    val version: String,
    val skipped: Int = 0,
) {
    companion object {
        val EMPTY = RuleSet(emptyList(), emptyList(), "builtin")

        private val NETWORKS = setOf(MobileMoneyParser.NETWORK_MPESA, MobileMoneyParser.NETWORK_AIRTEL)
        private val DIRECTIONS = setOf(MobileMoneyParser.OUT, MobileMoneyParser.IN, MobileMoneyParser.OTHER)
        private val KINDS = setOf(
            MobileMoneyParser.KIND_AUTO, MobileMoneyParser.KIND_PERSON, MobileMoneyParser.KIND_BUSINESS,
            MobileMoneyParser.KIND_AGENT, MobileMoneyParser.KIND_SELF, MobileMoneyParser.KIND_UNKNOWN,
        )

        /**
         * Build a rule set from rows of the parser_rules table. Rows with a bad network, an
         * unknown transaction type, an unanchored sender pattern, an over-long or uncompilable
         * regex are skipped and counted, never fatal.
         */
        fun fromRows(rows: List<Map<String, Any?>>, version: String): RuleSet {
            val senders = mutableListOf<Pair<Regex, String>>()
            val rules = mutableListOf<ParserRule>()
            var skipped = 0
            for (row in rows) {
                val network = row["network"] as? String
                val pattern = row["pattern"] as? String
                val type = (row["transaction_type"] as? String)?.takeIf { it.isNotBlank() }
                if (network !in NETWORKS || pattern.isNullOrBlank() || pattern.length > 1000 ||
                    type == null || type !in MobileMoneyParser.RULE_TYPES
                ) {
                    skipped++
                    continue
                }
                if (type == MobileMoneyParser.TYPE_SENDER && !(pattern.startsWith("^") && pattern.endsWith("$"))) {
                    skipped++
                    continue
                }
                val regex = try {
                    Regex(MobileMoneyParser.expandPattern(pattern), RegexOption.IGNORE_CASE)
                } catch (e: Exception) {
                    skipped++
                    continue
                }
                if (type == MobileMoneyParser.TYPE_SENDER) {
                    senders.add(regex to network!!)
                    continue
                }
                val direction = (row["direction"] as? String)?.takeIf { it in DIRECTIONS } ?: MobileMoneyParser.OTHER
                val kind = (row["kind"] as? String)?.takeIf { it in KINDS } ?: MobileMoneyParser.KIND_AUTO
                val status = if (row["status"] == MobileMoneyParser.STATUS_PARTIAL) MobileMoneyParser.STATUS_PARTIAL
                else MobileMoneyParser.STATUS_OK
                rules.add(
                    ParserRule(
                        network = network!!,
                        priority = (row["priority"] as? Number)?.toInt() ?: 100,
                        type = type,
                        direction = direction,
                        kind = kind,
                        regex = regex,
                        amountGroups = listOfNotNull((row["amount_group"] as? Number)?.toInt()),
                        partyGroups = listOfNotNull((row["party_group"] as? Number)?.toInt()),
                        defaultParty = (row["default_party"] as? String)?.takeIf { it.isNotBlank() },
                        amountFromText = row["amount_from_text"] == true,
                        status = status,
                        id = (row["id"] as? Number)?.toLong() ?: 0L,
                    )
                )
            }
            rules.sortWith(compareBy({ it.priority }, { it.id }))
            return RuleSet(senders, rules, version, skipped)
        }
    }
}

/**
 * The single parser for both capture paths (live SMS receiver and inbox scan).
 *
 * Order of evaluation: server rules first (in priority order), then the built-in rules, then
 * the built-in "not a transaction" checks, then the unknown fallback. Every stage a server
 * rule can outrank comes after the server rules, so wording fixes ship without a new APK.
 * The balance a text prints is read (1.34+) and leaves the phone only with consent; names and
 * numbers never do. Counterparty hashes are keyed by a per-device salt.
 */
object MobileMoneyParser {
    const val NETWORK_MPESA = "mpesa"
    const val NETWORK_AIRTEL = "airtel"

    const val OUT = "out"
    const val IN = "in"
    const val OTHER = "other"

    const val STATUS_OK = "ok"
    const val STATUS_PARTIAL = "partial"
    const val STATUS_UNKNOWN = "unknown"

    const val KIND_AUTO = "auto"
    const val KIND_PERSON = "person"
    const val KIND_BUSINESS = "business"
    const val KIND_AGENT = "agent"
    const val KIND_SELF = "self"
    const val KIND_UNKNOWN = "unknown"

    /** Operator a counterparty number belongs to, from its prefix. */
    const val CP_SAFARICOM = "safaricom"
    const val CP_AIRTEL = "airtel"
    const val CP_TELKOM = "telkom"
    const val CP_OTHER = "other"
    const val CP_UNKNOWN = "unknown"

    const val TYPE_IGNORE = "ignore"
    const val TYPE_SENDER = "sender"
    const val TYPE_UNKNOWN = "unknown"
    const val TYPE_IGNORED = "ignored"

    /**
     * A payment the network refused: M-PESA's "Failed. You do not have enough money ..." and Airtel
     * Money's "Your transaction has failed ...". Kept so the record shows who has trouble paying.
     * Stored with its full text like every other row; what leaves the phone is the de-identified
     * wording (text_template), the time and the balance it prints. Never money.
     */
    const val TYPE_FAILED = "failed"

    /** Transaction types a rule may assign, plus the rule-only types. */
    val TRANSACTION_TYPES = setOf(
        "send", "receive", "paybill", "buy_goods", "bundle", "withdraw", "deposit", "airtime",
        "fuliza_draw", "fuliza_repay", "savings_deposit", "savings_withdraw", "reversal", TYPE_FAILED,
    )
    val RULE_TYPES = TRANSACTION_TYPES + setOf(TYPE_IGNORE, TYPE_SENDER)

    /** Placeholders accepted in server rule patterns. Each expands to exactly one capturing group. */
    const val AMT = """(?:KES|KSHS?)\.?\s*([\d,]+(?:\.\d+)?)"""

    /**
     * Bump whenever a change to the built-in rules alters what a stored message reads as. A phone
     * that updates re-reads every stored message on its next open or heartbeat (RemoteRules),
     * so the history it already holds gets the new reading, not just new messages.
     * 1: the rules as they stood when this app was split from Utafiti (October 2026).
     * 2: failed payments kept as their own type instead of dropped (M-PESA) or left unread (Airtel
     *    Money), and an Airtel text with no transaction id that reads like money kept unread
     *    instead of dropped (November 2026).
     */
    const val BUILTIN_VERSION = 2
    const val PHONE = """(\+?254\d{9}|0[17]\d{8})"""

    private const val DOLLAR = "$"

    /** Sender ids, upper-cased and stripped of everything but letters and digits. */
    private val BUILT_IN_SENDERS = mapOf(
        "MPESA" to NETWORK_MPESA,
        "MPESAKE" to NETWORK_MPESA,
        "SAFARICOMMPESA" to NETWORK_MPESA,
        "AIRTELMONEY" to NETWORK_AIRTEL,
        "AIRTEL" to NETWORK_AIRTEL,
        "AIRTELKE" to NETWORK_AIRTEL,
    )

    fun expandPattern(pattern: String): String =
        pattern.replace("{AMT}", AMT).replace("{PHONE}", PHONE)

    // ── Generic fields and "not a transaction" checks ──
    private val MPESA_REF = Regex("""^([A-Z0-9]{8,12})\s+confirmed""", RegexOption.IGNORE_CASE)
    private val MPESA_FAILED = Regex("""^(?:failed|insufficient|you do not have)""", RegexOption.IGNORE_CASE)
    private val AIRTEL_FAILED = Regex("""^\s*(?:your\s+)?transaction\s+(?:has\s+)?failed\b""", RegexOption.IGNORE_CASE)
    // An Airtel text with no transaction id is usually an offer. One that reads like money moving (a
    // verb that moves money next to an amount) is kept unread instead, so its wording reaches the desk
    // and a rule can be written; one that opens like an advert ("Dial", "Get", "Withdraw at ...") is not.
    private val MONEY_LIKE = Regex(
        """\b(?:sent|paid|received|withdrawn|deposited|transferred)\b[^.]{0,80}?(?:kshs?|kes|shs?)\.?\s*\d|(?:kshs?|kes|shs?)\.?\s*\d[^.]{0,40}?\b(?:sent|paid|received|withdrawn|deposited|transferred)\b""",
        RegexOption.IGNORE_CASE,
    )
    private val ADVERT_OPENING = Regex(
        """^\W*(?:dial|buy|get|grab|enjoy|pata|nunua|piga|bonyeza|tuma|jibu|reply|send|visit|download|click|join|jiunge|shinda|win|stand\s+a\s+chance|hurry|don'?t\s+miss|usikose|introducing|try|jaribu|recharge|renew|subscribe|sign\s+up|upgrade|switch|tap|go\s+to|check\s+out|now\s+you\s+can|did\s+you\s+know|je\s+wajua""" +
            """|withdraw|deposit|top\s*up|save|pay|lipa|transact|use|earn|borrow|kopa|ongeza|furahia|tumia|weka|toa|shop|order|watch|listen|play|refer|invite|claim|redeem|activate|register|sajili|apply|book|spend)\b""",
        RegexOption.IGNORE_CASE,
    )
    // Airtime credited to the line, which moves no money: Airtel's airtime loan ("You have received
    // airtime of Ksh 20 at a service fee of Ksh 2 ...") and airtime sent from another line ("You have
    // received KSH 50 Airtime from 733123456.Balance is KSH 63", where the balance is airtime, not the
    // wallet's). They share the money sender.
    private val AIRTEL_AIRTIME_CREDIT = Regex(
        """you\s+have\s+received\s+(?:airtime\s+of\s+(?:KES|KSHS?)|(?:KES|KSHS?)\.?\s*[\d,]+(?:\.\d+)?\s+airtime\b)""",
        RegexOption.IGNORE_CASE,
    )
    private val MPESA_BALANCE_QUERY = Regex("""your\s+(?:m-?pesa\s+|account\s+)?balance\s+was""", RegexOption.IGNORE_CASE)
    // Notices from M-PESA that move no money (1.34, from the field's unread wordings): M-Shwari and
    // KCB M-PESA balance statements, loan limits, a loan status, a loan request, a Pochi balance
    // enquiry. Dropped after the rules have had their chance, like a balance enquiry.
    private val MPESA_NOTICE = Regex(
        """your\s+m-?shwari\s+(?:deposit\s+)?account\s+balance\s+is|your\s+kcb\s+m-?pesa\s+balance\s+is""" +
            """|your\s+(?:m-?shwari\s+)?loan\s+limit\s+is|you\s+have\s+an\s+outstanding\s+loan\s+of""" +
            """|loan\s+request\s+will\s+be\s+processed|pochi\s+account\s+balance\s+was""",
        RegexOption.IGNORE_CASE,
    )
    // The wallet balance after the transaction (1.34): "New M-PESA balance is Ksh1,234.56", "Your
    // M-PESA balance is Ksh 25.00", "New M-PESA account balance is Ksh 150.00"; never the KCB
    // M-PESA or M-Shwari figure printed beside it. Airtel: "Bal: Ksh 94.0", "Bal:Ksh 170.0",
    // "Bal Ksh 49.0", "Your available balance is KES 1,000.00".
    private val MPESA_BALANCE = Regex("""(?<!kcb\s)m-?pesa\s+(?:account\s+)?balance\s+is\s+$AMT""", RegexOption.IGNORE_CASE)
    private val AIRTEL_BALANCE = Regex("""(?:\bbal(?:ance)?\s*:?\s*(?:is\s+)?|available\s+balance\s+is\s+)$AMT""", RegexOption.IGNORE_CASE)

    /** The wallet balance printed in [text], or null when it prints none. */
    fun balanceAfter(text: String, network: String): Double? =
        (if (network == NETWORK_MPESA) MPESA_BALANCE else AIRTEL_BALANCE).find(text)?.let { amount(it.groupValues[1]) }
    private val COST = Regex("""transaction\s+cost,?\s*$AMT""", RegexOption.IGNORE_CASE)
    // Fuliza states its own charge on each draw: "Access fee charged Ksh10.00", in older texts
    // "Interest charged Ksh 0.90". That, not any "Transaction cost", is a draw's fee.
    private val FULIZA_FEE = Regex("""(?:access\s+fee|interest)\s+charged,?\s*(?:of\s+)?$AMT""", RegexOption.IGNORE_CASE)
    private val FULIZA_FULLY = Regex("""used\s+to\s+fully\s+pay""", RegexOption.IGNORE_CASE)
    // "Total Fuliza M-PESA outstanding amount is Ksh448.14", "Total Fuliza M-PESA outstanding is
    // Ksh510.00", and on older repayment texts "Outstanding Fuliza M-PESA is Ksh 957.83".
    private val FULIZA_OUTSTANDING = Regex(
        """(?:fuliza\s+m-?pesa\s+outstanding\s+(?:amount\s+)?is|outstanding\s+fuliza\s+m-?pesa\s+(?:amount\s+)?is)\s*$AMT""",
        RegexOption.IGNORE_CASE,
    )
    private val FULIZA_PARTIALLY = Regex("""used\s+to\s+partially\s+pay""", RegexOption.IGNORE_CASE)
    // Safaricom's own paybills and tills: a payment to one of these is a bundle, or airtime for
    // "SAFARICOM AIRTIME" (the two are kept apart). "SAFARICOM OFFERS" sells packages of data,
    // minutes or both and counts as a bundle.
    private val BUNDLE_NAME = Regex("""^SAFARICOM\b.*\b(?:DATA\s+BUNDLES?|BUNDLES?|AIRTIME|OFFERS?)\b|^(?:SAFARICOM\s+)?(?:DATA\s+)?BUNDLES?$|^(?:SAFARICOM\s+)?AIRTIME$""", RegexOption.IGNORE_CASE)
    private val AIRTIME_NAME = Regex("""^SAFARICOM\b.*\bAIRTIME\b|^(?:SAFARICOM\s+)?AIRTIME$""", RegexOption.IGNORE_CASE)
    private val WHITESPACE = Regex("""\s+""")
    private val KES_AMOUNT = Regex(AMT, RegexOption.IGNORE_CASE)
    // An id has at least one digit (1.35): "reference number is pending" is prose, not a reference.
    private val AIRTEL_REF = Regex("""\b(?:trans(?:action)?\s*id|txn\s*id|tid|ref(?:erence)?)\b\s*[:.]?\s*(?=[A-Z0-9.\-]*\d)([A-Z0-9][A-Z0-9.\-]{5,})(?![A-Za-z])""", RegexOption.IGNORE_CASE)
    // Real 2026 Airtel Money texts start with the id: eleven letters and digits, or eleven digits.
    private val AIRTEL_LEADING_REF = Regex("""^\s*(?=[A-Z0-9]*\d)([A-Z0-9]{10,12})(?=[.\s])""", RegexOption.IGNORE_CASE)
    // A "LAST 5 TXN" mini statement lists past transactions; it is not one.
    private val AIRTEL_STATEMENT = Regex("""^\s*LAST\s+\d+\s+TXN""", RegexOption.IGNORE_CASE)
    private val AIRTEL_FEE = Regex("""(?:charges?|fee|gharama)\s*:?\s*(?:of\s*)?$AMT""", RegexOption.IGNORE_CASE)
    // A real Airtel Money confirmation speaks in the first person about a completed action.
    private val AIRTEL_CONFIRMED = Regex(
        """\b(?:you\s+(?:have\s+)?(?:sent|received|paid|withdrawn|deposited|bought|transferred)""" +
            """|has\s+been\s+(?:sent|received|credited|debited|paid|deposited)""" +
            """|successfully\s+(?:sent|received|paid|withdrawn|deposited|transferred)""" +
            """|umetuma|umepokea|umelipa|umetoa|umenunua""" +
            """|received\s+(?:KES|KSHS?)|confirmed\.?\s+bundle\s+purchase|has\s+deposited|airtime\s+top\s+up\s+for\s+line""" +
            """|(?:KES|KSHS?)\.?\s*[\d,]+(?:\.\d+)?\s+(?:has\s+been\s+)?(?:sent|paid|received|deposited)\s+(?:to|from))\b""",
        RegexOption.IGNORE_CASE,
    )
    private val REVERSED_REF = Regex("""transaction\s+([A-Z0-9]{8,12})\b""", RegexOption.IGNORE_CASE)
    // "on 9/9/26 at 10:15 AM": the time Safaricom prints, Nairobi time, no seconds.
    // No trailing boundary: Safaricom prints "at 2:00 PMWithdraw" with no space after the meridian.
    private val BODY_TIME = Regex("""\bon\s+(\d{1,2})/(\d{1,2})/(\d{2,4})\s+(?:at\s+)?(\d{1,2}):(\d{2})(?:\s*([AP]M))?""", RegexOption.IGNORE_CASE)
    private val NAIROBI = java.util.TimeZone.getTimeZone("Africa/Nairobi")
    private val PHONE_TAIL = Regex("""^(.*?)\s*(\+?254\d{9}|0[17]\d{8})\s*$DOLLAR""")
    /**
     * "AIRTEL MONEY - JANE DOE 105252758": M-Pesa's label on money that crossed from the other
     * wallet. The label decides the network, not the number's prefix: 01 numbers are split
     * between the operators and a ported number keeps its old prefix. Other wallets go in here
     * as their wording is seen.
     */
    private val CROSS_NETWORK_AIRTEL = Regex("""^\s*AIRTEL\s*MONEY\s*[-:]\s*""", RegexOption.IGNORE_CASE)
    /** The same label prints the sender's number without its leading zero. */
    private val BARE_LOCAL_PHONE = Regex("""^(.*?)\s*([17]\d{8})\s*$DOLLAR""")
    /**
     * Money in from a person: M-Pesa masks the sender's number, "paul othiambo 0706***006".
     * Enough digits show to name the operator, not enough to dial, so the number is not kept and
     * the counterparty hash stays on the name.
     */
    private val MASKED_PHONE_TAIL = Regex("""^(.*?)\s*((?:\+?254|0)\d{2,3}\*{2,}\d{2,4})\s*$DOLLAR""")
    private val AGENT_PREFIX = Regex("""^\s*\d+\s*-\s*""")
    private val SPACES = Regex("""\s+""")
    private val NOT_ALNUM = Regex("[^A-Za-z0-9]")

    private fun rule(
        network: String, priority: Int, type: String, direction: String, kind: String, pattern: String,
        amountGroups: List<Int> = emptyList(), partyGroups: List<Int> = emptyList(),
        defaultParty: String? = null, amountFromText: Boolean = false, status: String = STATUS_OK,
    ) = ParserRule(
        network, priority, type, direction, kind, Regex(pattern, RegexOption.IGNORE_CASE),
        amountGroups, partyGroups, defaultParty, amountFromText, status,
    )

    // ── Built-in M-Pesa rules, tried in this order ──
    private val MPESA_RULES = listOf(
        rule(NETWORK_MPESA, 10, "paybill", OUT, KIND_BUSINESS,
            """$AMT\s+sent\s+to\s+(.+?)\s+for\s+account\s+(.+?)\s+on\s+\d""", listOf(1), listOf(2)),
        rule(NETWORK_MPESA, 20, "buy_goods", OUT, KIND_BUSINESS,
            """$AMT\s+paid\s+to\s+(.+?)\.?\s+on\s+\d""", listOf(1), listOf(2)),
        // Own-account moves (1.34, real 2026 wordings from the field): money between the personal
        // M-PESA and a Pochi la Biashara wallet, and KCB M-PESA moves whose verb is misspelt
        // ("transfered"), so the "transferred" rules below never saw them. Ahead of the send rule.
        rule(NETWORK_MPESA, 21, "savings_withdraw", OTHER, KIND_SELF,
            """$AMT\s+has\s+been\s+moved\s+from\s+your\s+pochi\s+account\s+to\s+your\s+m-?pesa\s+account""", listOf(1),
            defaultParty = "POCHI"),
        rule(NETWORK_MPESA, 22, "savings_deposit", OTHER, KIND_SELF,
            """$AMT\s+has\s+been\s+moved\s+from\s+your\s+m-?pesa\s+account\s+to\s+your\s+pochi\s+account""", listOf(1),
            defaultParty = "POCHI"),
        rule(NETWORK_MPESA, 23, "savings_withdraw", OTHER, KIND_SELF,
            """you\s+have\s+\S+\s+$AMT\s+from\s+your\s+kcb\s+m-?pesa\s+account""", listOf(1),
            defaultParty = "KCB M-PESA"),
        rule(NETWORK_MPESA, 24, "savings_deposit", OTHER, KIND_SELF,
            """$AMT\s+\S+\s+to\s+kcb\s+m-?pesa\s+account\s+on\s+\d""", listOf(1),
            defaultParty = "KCB M-PESA"),
        rule(NETWORK_MPESA, 25, "savings_deposit", OTHER, KIND_SELF,
            """your\s+loan\s+repayment\s+of\s+$AMT\s+from\s+your\s+m-?pesa\s+account\s+to\s+kcb\s+m-?pesa""", listOf(1),
            defaultParty = "KCB M-PESA LOAN"),
        rule(NETWORK_MPESA, 26, "savings_deposit", OTHER, KIND_SELF,
            """loan\s+of\s+$AMT\s+repaid\s+from\s+(?:your\s+)?m-?pesa""", listOf(1),
            defaultParty = "M-SHWARI LOAN"),
        rule(NETWORK_MPESA, 30, "send", OUT, KIND_AUTO,
            """$AMT\s+sent\s+to\s+(.+?)\.?\s+on\s+\d""", listOf(1), listOf(2)),
        rule(NETWORK_MPESA, 40, "receive", IN, KIND_AUTO,
            """you\s+have\s+received\s+$AMT\s+from\s+(.+?)\.?\s+on\s+\d""", listOf(1), listOf(2)),
        rule(NETWORK_MPESA, 50, "withdraw", OUT, KIND_AGENT,
            """withdraw\s+$AMT\s+from\s+(.+?)(?:\s+new\s+m-?pesa|\.\s|\.?$DOLLAR)""", listOf(1), listOf(2)),
        rule(NETWORK_MPESA, 60, "deposit", IN, KIND_AGENT,
            """give\s+$AMT\s+cash\s+to\s+(.+?)(?:\s+new\s+m-?pesa|\.\s|\.?$DOLLAR)""", listOf(1), listOf(2)),
        rule(NETWORK_MPESA, 70, "airtime", OUT, KIND_AUTO,
            """you\s+bought\s+$AMT\s+of\s+airtime(?:\s+for\s+(\+?\d{9,12}))?""", listOf(1), listOf(2),
            defaultParty = "OWN AIRTIME"),
        rule(NETWORK_MPESA, 80, "fuliza_draw", OTHER, KIND_SELF,
            """fuliza\s+m-?pesa\s+amount\s+is\s+$AMT|amount\s+used\s+$AMT""", listOf(1, 2),
            defaultParty = "FULIZA"),
        rule(NETWORK_MPESA, 90, "fuliza_repay", OTHER, KIND_SELF,
            """$AMT\s+from\s+your\s+m-?pesa\s+has\s+been\s+used\s+to\s+(?:fully\s+|partially\s+)?pay""", listOf(1),
            defaultParty = "FULIZA"),
        rule(NETWORK_MPESA, 100, "savings_deposit", OTHER, KIND_SELF,
            """$AMT\s+transferred\s+to\s+(m-?shwari|kcb\s+m-?pesa)\s+account""", listOf(1), listOf(2)),
        rule(NETWORK_MPESA, 110, "savings_withdraw", OTHER, KIND_SELF,
            """$AMT\s+transferred\s+from\s+(m-?shwari|kcb\s+m-?pesa)\s+account|you\s+have\s+transferred\s+$AMT\s+from\s+your\s+(m-?shwari|kcb\s+m-?pesa)\s+account""",
            listOf(1, 3), listOf(2, 4)),
        rule(NETWORK_MPESA, 120, "reversal", OTHER, KIND_UNKNOWN,
            """revers(?:al|ed)""", amountFromText = true, status = STATUS_PARTIAL),
    )

    // ── Built-in Airtel Money rules (provisional wording); only run behind AIRTEL_CONFIRMED ──
    // Airtel Money wording is still unverified in the field: every documented phrasing is
    // covered (with or without "have", amount first, bracketed numbers, Swahili verbs), and every
    // match stays "partial" so the text is uploaded and the wording can be confirmed, then promoted.
    private val AIRTEL_RULES = listOf(
        rule(NETWORK_AIRTEL, 10, "send", OUT, KIND_AUTO,
            """(?:you\s+(?:have\s+)?(?:sent|transferred)|umetuma)\s+$AMT\s+(?:to|kwa)\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_PARTIAL),
        // "77687700737 Confirmed. Bundle purchase successful of Ksh 15 via Airtel Networks Kenya Ltd on
        //  13/09/26 at 06:17 PM. Bal: Ksh 94.0." (real, 2026-09).
        rule(NETWORK_AIRTEL, 5, "bundle", OUT, KIND_SELF,
            """confirmed\.?\s+bundle\s+purchase""", amountFromText = true,
            defaultParty = "AIRTEL BUNDLES", status = STATUS_OK),
        // "P3RAIQM1E4N. Ksh 20 sent to NAME 2547XXXXXXXX on 16/09/26 11:20 AM. Bal:Ksh 170.0" (real, 2026-09).
        rule(NETWORK_AIRTEL, 11, "send", OUT, KIND_AUTO,
            """$AMT\s+(?:has\s+been\s+)?(?:sent|transferred)\s+to\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_OK),
        rule(NETWORK_AIRTEL, 20, "receive", IN, KIND_AUTO,
            """(?:you\s+(?:have\s+)?received|umepokea)\s+$AMT\s+(?:from|kutoka)\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_PARTIAL),
        // "U3RAGOD5ZEU. Ksh 200 received from NAME 2547XXXXXXXX on ..." (real, 2026-09).
        rule(NETWORK_AIRTEL, 21, "receive", IN, KIND_AUTO,
            """$AMT\s+(?:has\s+been\s+)?received\s+from\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_OK),
        // "TID:93RAIRQ77FS. Received Ksh 10 from NAME 2547XXXXXXXX on 16/09/26 11:31 AM. Bal:Ksh 190.0
        //  Sender TID:UIG4R73WQV." (real, 2026-09; the sender TID is the M-Pesa code of a cross-network transfer).
        rule(NETWORK_AIRTEL, 22, "receive", IN, KIND_AUTO,
            """\breceived\s+$AMT\s+from\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_OK),
        rule(NETWORK_AIRTEL, 30, "paybill", OUT, KIND_BUSINESS,
            """(?:you\s+(?:have\s+)?paid|umelipa)\s+$AMT\s+(?:to|kwa)\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_PARTIAL),
        // "Y3R43N9LBUN. Ksh 20 paid to SHOP NAME account 536917 on 11/09/2026 19:15. Fee Ksh 0. Bal:Ksh 109.0.
        //  MPESA ID:UIBS06VLWN", "... Ksh 1,400 paid to Lipa Na Mpesa via Airtel Money on ..." and
        // "63RAXZPKKIK Confirmed. Ksh 100 successfully paid to KCB BANK AGENT on ..." (all real, 2026-09).
        rule(NETWORK_AIRTEL, 31, "paybill", OUT, KIND_BUSINESS,
            """$AMT\s+(?:has\s+been\s+)?(?:successfully\s+)?paid\s+to\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR)""",
            listOf(1), listOf(2), status = STATUS_OK),
        // "N3RAY7QBHIP Confirmed. You have withdrawn Ksh 100 from 400604 AGENT NAME on 16/09/26 at 07:13 PM.
        //  Fee: Ksh 11 Bal Ksh 49.0." (real, 2026-09).
        rule(NETWORK_AIRTEL, 40, "withdraw", OUT, KIND_AGENT,
            """(?:you\s+(?:have\s+)?withdrawn|umetoa)\s+$AMT(?:\s+(?:from|kutoka)\s+(.+?)(?:\.\s|\s+account\s|\s+on\s|\s+charge|\s+fee|\s+gharama|\s+your|\s+new\s+bal|\s+bal(?:ance)?|\s+trans|\s+txn|\s+tid|\s+ref|\s+receipt|\.?$DOLLAR))?""",
            listOf(1), listOf(2), status = STATUS_OK),
        // "E3RAXX528TC. KCB BANK AGENT has deposited Ksh 150 to your account on 16/09/26 07:04 PM. Bal: Ksh 160.0."
        // and "AGENT NAME has deposited Ksh 100 to your account on ... PM.Bal: Ksh 160.0. TID V3RAY6227A9." (real, 2026-09).
        rule(NETWORK_AIRTEL, 49, "deposit", IN, KIND_AGENT,
            """(?:^|\.\s*)([^.]+?)\s+has\s+deposited\s+$AMT""",
            listOf(2), listOf(1), status = STATUS_OK),
        rule(NETWORK_AIRTEL, 50, "deposit", IN, KIND_AGENT,
            """(?:you\s+(?:have\s+)?deposited|$AMT\s+(?:has\s+been\s+)?deposited)\s*(?:$AMT)?""",
            listOf(1, 2), status = STATUS_PARTIAL),
        rule(NETWORK_AIRTEL, 60, "airtime", OUT, KIND_SELF,
            """(?:you\s+(?:have\s+)?bought|umenunua)\s+$AMT\s+(?:of\s+|worth\s+of\s+)?(?:airtime|bundle|data)""", listOf(1),
            defaultParty = "OWN AIRTIME", status = STATUS_PARTIAL),
        // "12345678901 Successful. Airtime top up for line 7XXXXXXXX of Ksh 50 is successful. Bal: Ksh 140.0"
        // (real, 2026-10). Own line or another: the text does not say, so the row stays partial.
        rule(NETWORK_AIRTEL, 61, "airtime", OUT, KIND_SELF,
            """airtime\s+top\s+up\s+for\s+line\s+\d{9,12}\s+of\s+$AMT\s+is\s+successful""", listOf(1),
            defaultParty = "AIRTIME", status = STATUS_PARTIAL),
    )

    private fun normalizeSender(address: String?): String = (address ?: "").replace(NOT_ALNUM, "").uppercase()

    /** Whether capture should hand a message from [address] to [parse] at all: the money senders only. */
    fun isCapturedSender(address: String?, remote: RuleSet = RuleSet.EMPTY): Boolean =
        networkForSender(address, remote) != null

    /**
     * Which network a sender id belongs to, or null for any other sender. Built-in ids match
     * exactly; server sender rules are anchored regexes matched against the whole id.
     */
    fun networkForSender(address: String?, remote: RuleSet = RuleSet.EMPTY): String? {
        val a = normalizeSender(address)
        if (a.isEmpty()) return null
        for ((regex, network) in remote.senders) if (regex.matches(a)) return network
        return BUILT_IN_SENDERS[a]
    }

    /**
     * Parse one SMS. Returns null for senders that are not a mobile money network, for messages
     * an ignore rule matches, and for the built-in "not a transaction" cases (M-Pesa failures and
     * balance enquiries, Airtel texts without a reference).
     * [salt] keys the counterparty pseudonym; pass the device salt from SyncConfigStore.
     */
    fun parse(
        body: String?, sender: String?, timestampMs: Long,
        remote: RuleSet = RuleSet.EMPTY, salt: String = "",
    ): Transaction? {
        val text = body?.trim().orEmpty()
        if (text.isEmpty()) return null
        val network = networkForSender(sender, remote) ?: return null

        val ref: String?
        val cost: Double?
        val hasAmount = KES_AMOUNT.containsMatchIn(text)
        if (network == NETWORK_MPESA) {
            ref = MPESA_REF.find(text)?.groupValues?.get(1)?.uppercase()
            cost = COST.find(text)?.let { amount(it.groupValues[1]) }
        } else {
            ref = AIRTEL_REF.find(text)?.groupValues?.get(1)?.trimEnd('.')
                ?: AIRTEL_LEADING_REF.find(text)?.groupValues?.get(1)?.uppercase()
            cost = AIRTEL_FEE.find(text)?.let { amount(it.groupValues[1]) }
        }
        val hash = messageKey(text, timestampMs, ref != null)
        val senderText = sender.orEmpty()

        // 1. Server rules, in priority order.
        for (rule in remote.rules) {
            if (rule.network != network) continue
            val match = rule.regex.find(text) ?: continue
            if (rule.type == TYPE_IGNORE) return ignoredRow(text, network, senderText, timestampMs, ref, hash)
            return fromRule(rule, match, network, text, senderText, timestampMs, ref, cost, hash, salt)
        }

        // 2. Built-in rules. Airtel wording is a guess, so it only applies to texts that read
        //    like a confirmation; promotional copy falls through to the unknown row instead.
        val builtIns = when (network) {
            NETWORK_MPESA -> MPESA_RULES
            else -> if (ref != null && AIRTEL_CONFIRMED.containsMatchIn(text)) AIRTEL_RULES else emptyList()
        }
        for (rule in builtIns) {
            val match = rule.regex.find(text) ?: continue
            return fromRule(rule, match, network, text, senderText, timestampMs, ref, cost, hash, salt)
        }

        // 3. Built-in "not a transaction" checks, after the rules so a server rule can outrank them.
        if (network == NETWORK_MPESA) {
            if (MPESA_FAILED.containsMatchIn(text)) return failedRow(text, network, senderText, timestampMs, ref, hash)
            if (MPESA_BALANCE_QUERY.containsMatchIn(text) || MPESA_NOTICE.containsMatchIn(text)) return null
            // Every M-Pesa transaction text opens with its code and "Confirmed". With neither, it is
            // service or marketing copy ("Win Ksh 1M...", "unable to progress your opt in request"),
            // and storing it as an unread transaction would only flag participants for review.
            if (ref == null && !CONFIRMED_WORD.containsMatchIn(text)) return null
        } else {
            if (AIRTEL_FAILED.containsMatchIn(text)) return failedRow(text, network, senderText, timestampMs, ref, hash)
            // Every Airtel Money transaction text carries its id; the sender's priced offers
            // ("1.5GB for Ksh 50") do not, and a mini statement is not a transaction. A text with no
            // id that reads like money moving is kept unread rather than lost.
            if (AIRTEL_STATEMENT.containsMatchIn(text)) return null
            if (ref == null) {
                if (ADVERT_OPENING.containsMatchIn(text) || AIRTEL_AIRTIME_CREDIT.containsMatchIn(text)) return null
                if (!MONEY_LIKE.containsMatchIn(text)) return null
            }
        }

        // 4. Unknown: stored and uploaded with its text so a rule can be written.
        return unreadRow(text, network, senderText, timestampMs, ref, cost, hash, balanceAfter(text, network))
    }

    /** A refused payment: no money moved; the wallet balance it prints is kept like any other. */
    private fun failedRow(text: String, network: String, sender: String, ts: Long, ref: String?, hash: String): Transaction = Transaction(
        network = network, type = TYPE_FAILED, direction = OTHER, amount = null, cost = null,
        counterparty = null, counterpartyPhone = null, counterpartyHash = null,
        counterpartyKind = KIND_UNKNOWN, counterpartyNetwork = CP_UNKNOWN,
        ref = ref, reversedRef = null, bodyTime = bodyTime(text),
        occurredAt = ts, messageHash = hash, parseStatus = STATUS_OK,
        sender = sender, body = text, balanceAfter = balanceAfter(text, network),
    )

    /**
     * A text a server ignore rule matched: kept on the phone as an ignored row, body and all, so a
     * rule written too broadly is undone by the next re-read instead of having thrown the text away
     * for good. A text first stored this way is not uploaded (insertIfNew marks it synced); a row
     * already on the server that a new rule ignores goes up once, so the server stops counting it.
     */
    private fun ignoredRow(text: String, network: String, sender: String, ts: Long, ref: String?, hash: String): Transaction = Transaction(
        network = network, type = TYPE_IGNORED, direction = OTHER, amount = null, cost = null,
        counterparty = null, counterpartyPhone = null, counterpartyHash = null,
        counterpartyKind = KIND_UNKNOWN, counterpartyNetwork = CP_UNKNOWN,
        ref = ref, reversedRef = null, bodyTime = null,
        occurredAt = ts, messageHash = hash, parseStatus = STATUS_OK,
        sender = sender, body = text,
    )

    /** The unread row: stored, and uploaded as a scrubbed template (the masked text too where the desk allows), so a rule can be written. */
    private fun unreadRow(
        text: String, network: String, sender: String, ts: Long, ref: String?, cost: Double?, hash: String, balance: Double? = null,
    ): Transaction = Transaction(
        network = network, type = TYPE_UNKNOWN, direction = OTHER, amount = null, cost = cost,
        counterparty = null, counterpartyPhone = null, counterpartyHash = null,
        counterpartyKind = KIND_UNKNOWN, counterpartyNetwork = CP_UNKNOWN,
        ref = ref, reversedRef = null, bodyTime = bodyTime(text),
        occurredAt = ts, messageHash = hash, parseStatus = STATUS_UNKNOWN,
        sender = sender, body = text, balanceAfter = balance,
    )

    private fun fromRule(
        rule: ParserRule, match: MatchResult, network: String, text: String, sender: String,
        ts: Long, ref: String?, cost: Double?, hash: String, salt: String,
    ): Transaction {
        var type = rule.type
        var kind = rule.kind

        val amountText = rule.amountGroups
            .map { match.groupValues.getOrNull(it).orEmpty() }
            .firstOrNull { it.isNotEmpty() }
        var amt = amountText?.let { amount(it) }
        if (amt == null && rule.amountFromText) amt = firstAmount(text)

        val partyRaw = rule.partyGroups
            .map { match.groupValues.getOrNull(it).orEmpty() }
            .firstOrNull { it.isNotBlank() }
        var name: String? = null
        var phone: String? = null
        var maskedNumber: String? = null
        var labelNetwork: String? = null
        if (partyRaw != null) {
            when (kind) {
                KIND_AGENT -> name = agentName(partyRaw)
                KIND_SELF -> name = productName(partyRaw)
                else -> {
                    // "JOHN DOE (0712345678)": brackets become spaces so the number is found.
                    var party = partyRaw.replace(BRACKETS, " ").replace(WHITESPACE, " ").trim()
                    val label = CROSS_NETWORK_AIRTEL.find(party)
                    if (label != null) {
                        labelNetwork = CP_AIRTEL
                        party = party.removeRange(label.range).trim()
                    }
                    val masked = if (label == null) MASKED_PHONE_TAIL.find(party) else null
                    if (masked != null) {
                        name = cleanName(masked.groupValues[1]).takeIf { it.isNotEmpty() }
                        maskedNumber = masked.groupValues[2]
                        labelNetwork = networkFromMasked(masked.groupValues[2])
                        if (kind == KIND_AUTO) kind = KIND_PERSON
                    } else {
                        val (n, p) = splitPhone(party, allowBareLocal = label != null)
                        name = n?.trim()?.takeIf { it.isNotEmpty() }
                        phone = p
                        if (kind == KIND_AUTO) kind = if (p != null) KIND_PERSON else KIND_BUSINESS
                    }
                }
            }
        }
        if (name == null && phone == null) {
            if (rule.defaultParty != null) {
                name = rule.defaultParty
                if (kind == KIND_AUTO) kind = KIND_SELF
            } else if (kind == KIND_AUTO) {
                kind = KIND_UNKNOWN
            }
        }

        if ((type == "paybill" || type == "buy_goods") && name != null && BUNDLE_NAME.containsMatchIn(name)) {
            type = if (AIRTIME_NAME.containsMatchIn(name)) "airtime" else "bundle"
        }
        if (type == "airtime" && network == NETWORK_AIRTEL && text.contains("bundle", ignoreCase = true)) type = "bundle"
        if (network == NETWORK_AIRTEL && type == "paybill") {
            if (name != null && AGENT_WORD.containsMatchIn(name)) {
                // "paid to KCB BANK AGENT": a bank agent pushes the cash-out as a payment.
                type = "withdraw"
                kind = KIND_AGENT
            } else if (!ACCOUNT_WORD.containsMatchIn(text)) {
                type = "buy_goods"   // a till: no account number
            }
        }
        // An "MPESA ID" on an Airtel Money payment is the receipt M-Pesa issued for it: the money
        // crossed to a Safaricom till or paybill, whatever the (absent) number prefix says.
        val cpNetwork = when {
            network == NETWORK_AIRTEL && MPESA_ID.containsMatchIn(text) -> CP_SAFARICOM
            labelNetwork != null -> labelNetwork
            else -> counterpartyNetwork(phone)
        }

        val reversedRef = if (type == "reversal") REVERSED_REF.find(text)?.groupValues?.get(1)?.uppercase() else null

        // Fuliza: a draw's fee is its access fee; a repayment says whether it cleared the balance,
        // which is what lets the server total the daily charges over a closed episode.
        val fee = if (type == "fuliza_draw") FULIZA_FEE.find(text)?.let { amount(it.groupValues[1]) } else cost
        val cleared = if (type != "fuliza_repay") null else when {
            FULIZA_FULLY.containsMatchIn(text) -> true
            FULIZA_PARTIALLY.containsMatchIn(text) -> false
            else -> null
        }
        val outstanding = if (type.startsWith("fuliza")) FULIZA_OUTSTANDING.find(text)?.let { amount(it.groupValues[1]) } else null

        return Transaction(
            network = network, type = type, direction = rule.direction, amount = amt, cost = fee,
            counterparty = name, counterpartyPhone = phone,
            counterpartyHash = counterpartyHash(name, phone, salt),
            counterpartyKind = kind, counterpartyNetwork = cpNetwork,
            ref = ref, reversedRef = reversedRef, bodyTime = bodyTime(text),
            occurredAt = ts, messageHash = hash, parseStatus = rule.status,
            sender = sender, body = text, fulizaCleared = cleared, fulizaOutstanding = outstanding,
            counterpartyLinkHash = counterpartyLink(phone, maskedNumber, salt),
            balanceAfter = balanceAfter(text, network),
        )
    }

    // ── helpers ──

    private fun amount(s: String): Double? = s.replace(",", "").toDoubleOrNull()

    private fun firstAmount(text: String): Double? = KES_AMOUNT.find(text)?.let { amount(it.groupValues[1]) }

    private fun cleanName(raw: String): String =
        raw.trim().trimEnd('.', ',', ';', ':').replace(SPACES, " ").trim().uppercase()

    /** Splits "JOHN DOE 0722123456" into (name, normalised phone). */
    private fun splitPhone(raw: String, allowBareLocal: Boolean = false): Pair<String?, String?> {
        val cleaned = raw.trim().trimEnd('.', ',', ';', ':')
        val m = PHONE_TAIL.find(cleaned) ?: if (allowBareLocal) BARE_LOCAL_PHONE.find(cleaned) else null
        if (m != null) {
            val name = cleanName(m.groupValues[1]).ifEmpty { null }
            return Pair(name, normalizePhone(m.groupValues[2]))
        }
        return Pair(cleanName(raw).ifEmpty { null }, null)
    }

    private fun agentName(raw: String): String = cleanName(raw.replace(AGENT_PREFIX, ""))

    private fun productName(raw: String): String {
        val u = raw.uppercase()
        return when {
            u.contains("KCB") -> "KCB M-PESA"
            u.contains("SHWARI") -> "M-SHWARI"
            else -> cleanName(raw)
        }
    }

    /**
     * The date and time printed in an M-Pesa confirmation ("on 9/9/26 at 10:15 AM"), as epoch
     * milliseconds in Nairobi time, or null when the message carries none. Kept beside the SMS
     * timestamp: it is the network's own clock, but has no seconds and no year beyond two digits.
     */
    fun bodyTime(text: String): Long? {
        val m = BODY_TIME.find(text) ?: return null
        return try {
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            var year = m.groupValues[3].toInt()
            if (year < 100) year += 2000
            // With AM/PM it is a 12-hour clock; without (Airtel's "19:15") it is already 24-hour.
            val meridian = m.groupValues[6].uppercase()
            var hour = m.groupValues[4].toInt()
            if (meridian.isNotEmpty()) {
                hour %= 12
                if (meridian == "PM") hour += 12
            }
            val minute = m.groupValues[5].toInt()
            if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
            val cal = java.util.Calendar.getInstance(NAIROBI)
            cal.clear()
            cal.set(year, month - 1, day, hour, minute, 0)
            cal.timeInMillis
        } catch (e: Exception) {
            null
        }
    }

    /** Kenyan numbers to 254XXXXXXXXX. */
    fun normalizePhone(raw: String): String {
        val d = raw.filter { it.isDigit() }
        return when {
            d.startsWith("254") -> d
            d.startsWith("0") && d.length == 10 -> "254" + d.substring(1)
            d.length == 9 -> "254$d"
            else -> d
        }
    }

    /**
     * Operator from the number prefix (Communications Authority allocations as of 2025; number
     * portability is rare enough to ignore). Non-identifying, so it is uploaded.
     */
    /**
     * Both sides of one relationship under one key: the number cut down to what money in shows,
     * "0712***678". Null when no number of either kind is in the text. A thousand real numbers
     * share any one of these, which is why it is a link and not the counterparty hash itself.
     */
    fun maskedKey(phone: String?, masked: String?): String? {
        if (phone != null) {
            if (!phone.startsWith("254") || phone.length != 12) return null
            val local = "0" + phone.substring(3)
            return local.substring(0, 4) + "***" + local.takeLast(3)
        }
        if (masked == null) return null
        val head = masked.takeWhile { it.isDigit() || it == '+' }.filter { it.isDigit() }
        val tail = masked.takeLastWhile { it.isDigit() }
        val local = when {
            head.startsWith("254") -> "0" + head.substring(3)
            head.startsWith("0") -> head
            else -> "0$head"
        }
        if (local.length < 4 || tail.length < 3) return null
        return local.substring(0, 4) + "***" + tail.takeLast(3)
    }

    /** HMAC of [maskedKey] under the device salt; same shape as the counterparty hash. */
    fun counterpartyLink(phone: String?, masked: String?, salt: String): String? {
        val key = maskedKey(phone, masked) ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(("utafiti:" + salt).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(("m:$key").toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }
    }

    /** Operator behind a masked number, from the digits M-Pesa still shows ("0706***006"). */
    fun networkFromMasked(masked: String): String {
        val digits = masked.takeWhile { it.isDigit() || it == '+' }.filter { it.isDigit() }
        val local = when {
            digits.startsWith("254") -> digits.substring(3)
            digits.startsWith("0") -> digits.substring(1)
            else -> digits
        }
        if (local.length < 3) return CP_UNKNOWN
        return counterpartyNetwork("254" + local.substring(0, 3) + "000000")
    }

    /**
     * The operator that holds the number's range, read off the Communications Authority's
     * numbering plan (April 2026 edition, which allots every mobile range at the 3-digit level),
     * plus 0180-0182, which Safaricom opened in August 2026. Kenya has had number portability
     * since 2011, so this names the range holder; a ported number keeps its old prefix.
     * The small licensees (Equitel 763-766, Faiba 747, the MVNOs, the IEBC kits on 749, the
     * shared test range 199) are "other". 0767 (Airtel until 2024, dropped from the plan since)
     * and every range the plan leaves unassigned are "unknown", not a guess.
     */
    fun counterpartyNetwork(phone: String?): String {
        if (phone == null || !phone.startsWith("254") || phone.length != 12) return CP_UNKNOWN
        val p3 = phone.substring(3, 6).toIntOrNull() ?: return CP_UNKNOWN
        return when (p3) {
            in 700..729, in 740..743, 745, 746, 748, in 757..759, 768, 769, in 790..799 -> CP_SAFARICOM
            in 110..119, in 140..146, in 180..182 -> CP_SAFARICOM
            in 730..739, in 750..756, 762, in 780..789, in 100..108 -> CP_AIRTEL
            in 770..779 -> CP_TELKOM
            744, 747, 749, 760, 761, in 763..766, 120, 124, 126, 128, 130, 199 -> CP_OTHER
            else -> CP_UNKNOWN
        }
    }

    private val CONFIRMED_WORD = Regex("""\bconfirmed\b""", RegexOption.IGNORE_CASE)
    private val AGENT_WORD = Regex("""\bAGENT\b""", RegexOption.IGNORE_CASE)
    private val ACCOUNT_WORD = Regex("""\baccount\s+\S""", RegexOption.IGNORE_CASE)
    private val MPESA_ID = Regex("""\bM-?PESA\s+ID\s*:""", RegexOption.IGNORE_CASE)
    /** The party Airtel prints on the second, generic text it sends for a payment to an M-Pesa till. */
    val GENERIC_PARTY = Regex("""^Lipa\s+Na\s+M-?pesa\b""", RegexOption.IGNORE_CASE)
    private val BRACKETS = Regex("""[()\[\]]""")
    private val MASKABLE_NUMBER = Regex("""(?<!\d)(?:\+?254\d{9}|0[17]\d{8})(?!\d)""")

    /**
     * Airtel's twin texts for one payment (same TID and amount): whether the text that just
     * arrived should replace the stored one. Only a named party ever displaces a generic one, so
     * the row ends the same whichever text arrived first (1.35, audit P01).
     */
    fun namedTwinWins(existingParty: String?, newParty: String?): Boolean {
        val existingGeneric = existingParty == null || GENERIC_PARTY.containsMatchIn(existingParty)
        val newGeneric = newParty == null || GENERIC_PARTY.containsMatchIn(newParty)
        return existingGeneric && !newGeneric
    }

    /**
     * Phone numbers in text that leaves the phone are masked the way the M-Pesa statement masks
     * them: operator prefix kept, middle hidden, last three kept (254722***268, 0722***268).
     * Enough to write a parser rule against, not enough to dial.
     */
    fun maskNumbers(text: String): String = MASKABLE_NUMBER.replace(text) { m ->
        val n = m.value
        val head = if (n.startsWith("0")) 4 else n.length - 6
        n.substring(0, head) + "***" + n.substring(n.length - 3)
    }

    /**
     * Stable pseudonym for a counterparty within one device: HMAC-SHA256 keyed by the device
     * salt over the phone number when present, else the name. Empty salt means "no salt yet",
     * which callers must treat as an error after enrolment.
     */
    fun counterpartyHash(name: String?, phone: String?, salt: String): String? {
        val key = phone?.takeIf { it.isNotEmpty() }?.let { "p:$it" }
            ?: name?.takeIf { it.isNotEmpty() }?.let { "n:$it" }
            ?: return null
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(("utafiti:" + salt).toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(key.toByteArray(Charsets.UTF_8)).take(10).joinToString("") { "%02x".format(it) }
    }

    /**
     * The local primary key and the server dedup key: SHA-256 over the trimmed body, so two
     * confirmations that differ only in their reference can never share a key (djb2 could
     * collide). A message with no reference of its own (a bundle confirmation) is scoped to
     * its minute (1.36; to its day before, which merged two identical purchases on one day),
     * so the live receiver and a later inbox scan agree on one key for one SMS; where the store
     * lacks date_sent and the two read different times, TransactionDb.insertIfNew treats the
     * same wording within two minutes as the same text.
     */
    fun messageKey(text: String, timestampMs: Long, hasReference: Boolean): String {
        val scope = if (hasReference) "" else "|" + (timestampMs / 60_000L)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(("utafiti-msg|" + text.trim() + scope).toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }

    /** djb2 over a string; used for the rule-set version tag. */
    fun djb2(msg: String): String {
        var hash = 0
        for (c in msg) {
            hash = ((hash shl 5) - hash) + c.code
        }
        return hash.toString()
    }
}
