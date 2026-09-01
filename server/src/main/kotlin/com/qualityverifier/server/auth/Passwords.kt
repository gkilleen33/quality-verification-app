package com.qualityverifier.server.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Argon2id password hashing.
 *
 * This is where an expensive hash actually earns its keep: unlike a refresh token, a
 * password is something a human chose, so there *is* a dictionary to slow down.
 *
 * Bouncy Castle rather than argon2-jvm: pure Java, no JNI and no bundled native
 * library to go wrong on a platform we did not test.
 *
 * Parameters follow OWASP's floor — 19 MiB, two passes, one lane — chosen with the box
 * in mind: memory cost is per concurrent hash, and this server has 3.7 GB shared with
 * Postgres. They are stored inside each hash, so raising them later re-hashes people
 * gradually on next sign-in instead of locking everybody out at once.
 */
object Passwords {

    private const val MEMORY_KIB = 19_456
    private const val ITERATIONS = 2
    private const val PARALLELISM = 1
    private const val HASH_BYTES = 32
    private const val SALT_BYTES = 16

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun hash(password: String): String {
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM)
        return "\$argon2id\$v=19\$m=$MEMORY_KIB,t=$ITERATIONS,p=$PARALLELISM" +
            "\$${encoder.encodeToString(salt)}\$${encoder.encodeToString(hash)}"
    }

    /**
     * False for a malformed stored hash rather than throwing. A corrupt row should fail
     * one sign-in, not take the endpoint down for everybody.
     */
    fun verify(password: String, encoded: String): Boolean {
        val parts = encoded.split('$').filter { it.isNotEmpty() }
        if (parts.size != 5 || parts[0] != "argon2id") return false
        val params = parts[2].split(',').mapNotNull { pair ->
            pair.split('=').takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val memory = params["m"]?.toIntOrNull() ?: return false
        val iterations = params["t"]?.toIntOrNull() ?: return false
        val parallelism = params["p"]?.toIntOrNull() ?: return false

        val salt = runCatching { decoder.decode(parts[3]) }.getOrNull() ?: return false
        val expected = runCatching { decoder.decode(parts[4]) }.getOrNull() ?: return false

        val actual = derive(password, salt, memory, iterations, parallelism, expected.size)
        // Constant time: a byte-by-byte early exit leaks how much of the hash matched.
        return MessageDigest.isEqual(actual, expected)
    }

    /**
     * Burns the same work as a real verification against a throwaway hash.
     *
     * Called when no user matches the phone number. Without it, "no such account"
     * returns in microseconds and a real account takes ~50ms, which turns the sign-in
     * endpoint into a way to enumerate who has an account — the generic error message
     * notwithstanding.
     */
    fun burnEquivalentWork(password: String) {
        derive(password, ByteArray(SALT_BYTES), MEMORY_KIB, ITERATIONS, PARALLELISM)
    }

    private fun derive(
        password: String,
        salt: ByteArray,
        memoryKib: Int,
        iterations: Int,
        parallelism: Int,
        length: Int = HASH_BYTES,
    ): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(memoryKib)
                .withIterations(iterations)
                .withParallelism(parallelism)
                .withSalt(salt)
                .build()
        )
        return ByteArray(length).also { generator.generateBytes(password.toByteArray(Charsets.UTF_8), it) }
    }
}
