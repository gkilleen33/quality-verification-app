package com.qualityverifier.server.mpesa

import com.qualityverifier.domain.CounterpartyBasis
import com.qualityverifier.domain.KenyanPhone
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A counterparty as stored: a 64-character hex code and what it was computed from. */
data class CounterpartyCode(val code: String, val basis: CounterpartyBasis)

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
 * What goes into the HMAC is the basis and the normalised value, "phone:+254712345678" or
 * "name:john doe", so a name can never share a code with a number.
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

        private val SPACES = Regex("""\s+""")
    }
}
