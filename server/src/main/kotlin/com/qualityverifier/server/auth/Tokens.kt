package com.qualityverifier.server.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Refresh token minting and hashing.
 *
 * The token the client keeps is 256 bits of CSPRNG output. What we store is its
 * SHA-256, so a database dump does not hand somebody a set of working credentials.
 * Comparison is constant-time, because a timing signal on a token lookup is a slow
 * but real way to confirm a guess.
 */
object Tokens {

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun mint(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    fun matches(token: String, expectedHash: String): Boolean =
        MessageDigest.isEqual(
            hash(token).toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
}
