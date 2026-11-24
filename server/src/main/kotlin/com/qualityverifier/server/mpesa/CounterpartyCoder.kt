package com.qualityverifier.server.mpesa

import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.domain.KenyanPhone
import com.qualityverifier.mpesa.MobileMoneyParser
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A counterparty as stored: a 64-character hex code and what it was computed from. */
data class CounterpartyCode(val code: String, val basis: CounterpartyBasis)

/**
 * Everything the server keeps of the other person on one payment (V42): the code it has
 * always kept, plus a code of the name and a code of the masked number, each null when the
 * payment carried nothing to make it from. Codes only; see [CounterpartyCoder.codes].
 */
data class CounterpartyCodes(
    val code: CounterpartyCode?,
    val nameCode: String?,
    val maskedCode: String?,
)

/**
 * Turns the person or business on the other side of an M-Pesa payment into a code, the only
 * form in which the server keeps it (Nathan, 9 November 2026). The one place this happens.
 *
 * HMAC-SHA256 under a study-wide key (`/kagua/mpesa/hmac-key`, read by `kagua-run`), so the
 * same number gives the same code in every fundi's records and payments can be matched
 * across workshops. Keyed, not a plain hash: a Kenyan mobile number is nine digits, and an
 * unkeyed SHA-256 of every one of them is a table anybody can build in an afternoon. There
 * is no fallback to an unkeyed hash; without the key the M-Pesa routes are not mounted.
 *
 * What goes into the HMAC is the basis and the normalised value, "phone:+254712345678",
 * "name:john doe" or, for [codes]' masked-number code, "masked:0712***678", so no two kinds
 * can ever share a code.
 *
 * - A number is normalised to E.164 by the rules the sign-up field uses ([KenyanPhone]), so
 *   "0712 345 678", "254712345678" and "+254712345678" are one person.
 * - A name, or a number those rules refuse, is normalised by [normaliseName]: trimmed,
 *   case-folded and with every run of spaces made one. M-Pesa prints the same business in
 *   capitals in one message and in mixed case in another; nothing else is changed, so two
 *   genuinely different spellings stay two codes.
 *
 * The plain value is never stored, logged or returned. This class holds the key and nothing
 * else, and its [toString] says so.
 */
class CounterpartyCoder(key: String) {

    private val keySpec: SecretKeySpec

    init {
        require(key.length >= MIN_KEY_LENGTH) { "the M-Pesa HMAC key is shorter than $MIN_KEY_LENGTH characters" }
        keySpec = SecretKeySpec(key.toByteArray(Charsets.UTF_8), ALGORITHM)
    }

    /**
     * The code for a counterparty given as [phone] or [name]. A number is preferred; null
     * when neither holds anything usable.
     */
    fun code(phone: String?, name: String?): CounterpartyCode? {
        phone?.let(::e164)?.let { return CounterpartyCode(hmac("phone:$it"), CounterpartyBasis.PHONE) }
        // A number the phone rules refuse (a foreign one, a till number) is still somebody;
        // it is coded as written, under the name basis.
        val asName = sequenceOf(phone, name).mapNotNull { it?.let(::normaliseName) }.firstOrNull()
            ?: return null
        return CounterpartyCode(hmac("name:$asName"), CounterpartyBasis.NAME)
    }

    /**
     * The three codes for one payment (row 32; Nathan, 17 November 2026).
     *
     * M-Pesa prints a full number on money sent and a masked one ("0720***123") on money
     * received from a person, so [code] gives a send and a receipt from the same person two
     * unrelated codes. The other two are made from what both sides share:
     *
     * - the name code: "name:" and the [normaliseName]d name, whenever [name] holds one. The
     *   same input as [code] uses for a name, so it equals the code of a row coded by name.
     * - the masked-number code: "masked:" and the number cut to what money received shows,
     *   the first four and last three digits of the local form, by [MobileMoneyParser.maskedKey],
     *   the parser's own rule. Made from [phone] when it is a Kenyan mobile number, otherwise
     *   from [masked] when that has the shape M-Pesa prints.
     *
     * Two payments are with the same person when their [code]s are equal with basis phone, or
     * when both their name codes and their masked-number codes are equal (docs/data-api.md).
     * The server stores the codes and computes no match.
     *
     * [code] is exactly [code] (phone, name): the masked number never changes it, so every
     * earlier row keeps its meaning.
     */
    fun codes(phone: String?, name: String?, masked: String?): CounterpartyCodes = CounterpartyCodes(
        code = code(phone, name),
        nameCode = name?.let(::normaliseName)?.let { hmac("name:$it") },
        maskedCode = maskedKey(phone, masked)?.let { hmac("masked:$it") },
    )

    private fun hmac(input: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(keySpec)
        return mac.doFinal(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    override fun toString(): String = "CounterpartyCoder(key=***)"

    companion object {
        private const val ALGORITHM = "HmacSHA256"

        /**
         * `openssl rand -base64 32` prints 44 characters. Anything much shorter is a key
         * somebody typed, and a typed key is a guessable one.
         */
        const val MIN_KEY_LENGTH = 32

        /** "+254712345678" for any form of a Kenyan mobile number the sign-up field accepts; null otherwise. */
        fun e164(raw: String): String? {
            val edit = KenyanPhone.edit("", raw)
            if (edit.refused != null) return null
            return KenyanPhone.toE164(edit.digits)
        }

        /**
         * Trim, case-fold, collapse spaces; null when nothing is left. Case-folded with the
         * root locale, so a server whose default locale is Turkish still folds "I" to "i".
         */
        fun normaliseName(raw: String): String? =
            raw.trim().lowercase(Locale.ROOT).replace(SPACES, " ").takeIf { it.isNotEmpty() }

        /**
         * "0720***123" for a Kenyan mobile number in any form [e164] accepts, or for a masked
         * number as M-Pesa prints it ("0720***123", "254720***123"); null otherwise. A full
         * number wins over a masked one. A masked value that is not that shape (a full number,
         * a name, anything longer than M-Pesa prints) gives null rather than a guess.
         */
        fun maskedKey(phone: String?, masked: String?): String? {
            phone?.let(::e164)?.let { return MobileMoneyParser.maskedKey(it.removePrefix("+"), null) }
            val printed = masked?.trim()?.takeIf { it.length <= MAX_MASKED_LENGTH && MASKED.matches(it) }
                ?: return null
            return MobileMoneyParser.maskedKey(null, printed)
        }

        private val SPACES = Regex("""\s+""")

        /** What M-Pesa prints for a person on money received, as the parser reads it (MASKED_PHONE_TAIL). */
        private val MASKED = Regex("""^(?:\+?254|0)\d{2,3}\*{2,}\d{2,4}$""")

        /** "+254720*****1234" is 16; anything much longer is not a masked number. */
        private const val MAX_MASKED_LENGTH = 24
    }
}
