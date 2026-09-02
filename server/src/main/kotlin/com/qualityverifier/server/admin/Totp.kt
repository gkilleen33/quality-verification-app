package com.qualityverifier.server.admin

import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.experimental.and

/**
 * TOTP, RFC 6238, for admin second factors.
 *
 * Written out rather than pulled in. The algorithm is a HMAC, a truncation and a modulo;
 * RFC 6238 publishes test vectors, so an implementation here can be checked against the
 * specification itself — which is stronger evidence than a dependency's own test suite,
 * and one fewer jar on a box that shares 3.7 GB with Postgres.
 *
 * SHA-1 is not a mistake: RFC 6238's default is HMAC-SHA1, it is what Google
 * Authenticator, Aegis and 1Password all assume for an `otpauth://` URI, and HMAC-SHA1 is
 * unaffected by SHA-1's collision weaknesses. Choosing SHA-256 here would be defensible
 * cryptographically and would silently produce wrong codes in most authenticator apps.
 */
object Totp {

    private const val DIGITS = 6
    private const val PERIOD_SECONDS = 30L

    /**
     * How many steps either side of now are accepted.
     *
     * One, so a code typed as it rolls over still works and a slow phone clock is
     * tolerated. That widens the window to 90 seconds; with a rate limit on the form and
     * six digits, guessing inside it is not the weak link.
     */
    private const val SKEW_STEPS = 1

    private val random = SecureRandom()

    /** A fresh 160-bit secret, the size RFC 4226 recommends for HMAC-SHA1. */
    fun newSecret(): String = Base32.encode(ByteArray(20).also(random::nextBytes))

    /**
     * Whether [code] is valid for [secret] at [atEpochSeconds].
     *
     * Non-digits are stripped first: people read codes as "123 456", and refusing that is
     * a support message rather than security.
     */
    fun verify(secret: String, code: String, atEpochSeconds: Long = now()): Boolean {
        val cleaned = code.filter(Char::isDigit)
        if (cleaned.length != DIGITS) return false
        val key = runCatching { Base32.decode(secret) }.getOrNull() ?: return false
        val step = atEpochSeconds / PERIOD_SECONDS
        return (-SKEW_STEPS..SKEW_STEPS).any { offset ->
            // Constant-time compare. A timing signal on a six-digit code is a thin channel,
            // but the comparison costs the same either way.
            constantTimeEquals(cleaned, generate(key, step + offset))
        }
    }

    /** The code for a given counter step. Public for the RFC test vectors. */
    fun generate(key: ByteArray, step: Long): String {
        val message = ByteArray(8)
        var value = step
        for (i in 7 downTo 0) {
            message[i] = (value and 0xff).toByte()
            value = value shr 8
        }
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        val hash = mac.doFinal(message)
        // Dynamic truncation, RFC 4226 §5.3: the low nibble of the last byte picks where
        // to read four bytes from.
        val offset = (hash[hash.size - 1] and 0x0f).toInt()
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (binary % 1_000_000).toString().padStart(DIGITS, '0')
    }

    /**
     * The `otpauth://` URI an authenticator app reads.
     *
     * The label carries an issuer so an admin with several accounts can tell which is
     * which, and the issuer is repeated as a parameter because apps disagree about which
     * one they read.
     */
    fun provisioningUri(secret: String, account: String, issuer: String = "Kagua"): String {
        val label = "${encode(issuer)}:${encode(account)}"
        return "otpauth://totp/$label?secret=$secret&issuer=${encode(issuer)}" +
            "&algorithm=SHA1&digits=$DIGITS&period=$PERIOD_SECONDS"
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8")
        // URLEncoder is form encoding, which is not quite URI encoding.
        .replace("+", "%20")

    private fun now() = System.currentTimeMillis() / 1000

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var difference = 0
        for (i in a.indices) difference = difference or (a[i].code xor b[i].code)
        return difference == 0
    }
}

/**
 * Base32, RFC 4648, no padding on encode.
 *
 * Authenticator apps take secrets in base32 and nothing else, and java.util.Base64 is a
 * different alphabet. Written here for the same reason as the TOTP above.
 */
object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder()
        var buffer = 0
        var bitsLeft = 0
        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xff)
            bitsLeft += 8
            while (bitsLeft >= 5) {
                out.append(ALPHABET[(buffer shr (bitsLeft - 5)) and 0x1f])
                bitsLeft -= 5
            }
        }
        if (bitsLeft > 0) out.append(ALPHABET[(buffer shl (5 - bitsLeft)) and 0x1f])
        return out.toString()
    }

    fun decode(encoded: String): ByteArray {
        // Padding and spaces are tolerated: a secret copied out of an app or a document
        // often carries both.
        val cleaned = encoded.uppercase().filter { it != '=' && !it.isWhitespace() }
        val out = ByteArray(cleaned.length * 5 / 8)
        var buffer = 0
        var bitsLeft = 0
        var index = 0
        for (character in cleaned) {
            val value = ALPHABET.indexOf(character)
            require(value >= 0) { "not base32" }
            buffer = (buffer shl 5) or value
            bitsLeft += 5
            if (bitsLeft >= 8) {
                out[index++] = ((buffer shr (bitsLeft - 8)) and 0xff).toByte()
                bitsLeft -= 8
            }
        }
        return out
    }
}
