package com.qualityverifier.server

import com.qualityverifier.server.admin.Base32
import com.qualityverifier.server.admin.Totp
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checked against the specifications rather than against itself.
 *
 * This is hand-written crypto plumbing guarding the admin portal, which can read every
 * customer's conversation. The only verification worth having is the published test
 * vectors: RFC 4226 Appendix D for HOTP, RFC 6238 Appendix B for TOTP, RFC 4648 §10 for
 * base32. If these pass, the codes agree with what an authenticator app will produce.
 */
class TotpTest {

    /** RFC 4226 Appendix D uses the ASCII secret "12345678901234567890". */
    private val rfcKey = "12345678901234567890".toByteArray()

    @Test
    fun `HOTP matches RFC 4226 Appendix D`() {
        val expected = listOf(
            "755224", "287082", "359152", "969429", "338314",
            "254676", "287922", "162583", "399871", "520489",
        )
        expected.forEachIndexed { counter, code ->
            assertEquals("counter $counter", code, Totp.generate(rfcKey, counter.toLong()))
        }
    }

    @Test
    fun `TOTP matches RFC 6238 Appendix B for SHA-1`() {
        // The RFC tabulates 8-digit codes; ours are the low 6 digits of the same value,
        // which is what every authenticator app shows.
        val vectors = mapOf(
            59L to "94287082",
            1111111109L to "07081804",
            1111111111L to "14050471",
            1234567890L to "89005924",
            2000000000L to "69279037",
            20000000000L to "65353130",
        )
        for ((seconds, eightDigits) in vectors) {
            val step = seconds / 30
            assertEquals(
                "at $seconds",
                eightDigits.takeLast(6),
                Totp.generate(rfcKey, step),
            )
        }
    }

    @Test
    fun `verify accepts the current code and the neighbouring steps`() {
        val secret = Base32.encode(rfcKey)
        val at = 1111111111L
        val step = at / 30
        assertTrue(Totp.verify(secret, Totp.generate(rfcKey, step), at))
        // A code typed as the clock rolls over, and a phone running slightly fast.
        assertTrue(Totp.verify(secret, Totp.generate(rfcKey, step - 1), at))
        assertTrue(Totp.verify(secret, Totp.generate(rfcKey, step + 1), at))
    }

    @Test
    fun `verify refuses a code from further away`() {
        val secret = Base32.encode(rfcKey)
        val at = 1111111111L
        val step = at / 30
        assertFalse(Totp.verify(secret, Totp.generate(rfcKey, step - 2), at))
        assertFalse(Totp.verify(secret, Totp.generate(rfcKey, step + 2), at))
    }

    @Test
    fun `a code read aloud with a space still works`() {
        // People read "123 456". Refusing that is a support message, not security.
        val secret = Base32.encode(rfcKey)
        val at = 1111111111L
        val code = Totp.generate(rfcKey, at / 30)
        assertTrue(Totp.verify(secret, "${code.take(3)} ${code.takeLast(3)}", at))
    }

    @Test
    fun `nonsense is refused rather than throwing`() {
        // These arrive from a form, so every one of them is somebody's typo or a scanner.
        val secret = Base32.encode(rfcKey)
        assertFalse(Totp.verify(secret, "", 0))
        assertFalse(Totp.verify(secret, "12345", 0))
        assertFalse(Totp.verify(secret, "1234567", 0))
        assertFalse(Totp.verify(secret, "abcdef", 0))
        // A stored secret that is not base32 must fail closed, not crash the login route.
        assertFalse(Totp.verify("not!base32!", "123456", 0))
    }

    @Test
    fun `a fresh secret is 160 bits and usable`() {
        val secret = Totp.newSecret()
        assertEquals(32, secret.length)          // 20 bytes in base32, unpadded
        assertEquals(20, Base32.decode(secret).size)
        val code = Totp.generate(Base32.decode(secret), 1)
        assertTrue(Totp.verify(secret, code, 30))
    }

    @Test
    fun `two secrets differ`() {
        assertTrue(Totp.newSecret() != Totp.newSecret())
    }

    @Test
    fun `base32 matches RFC 4648 section 10`() {
        val vectors = mapOf(
            "" to "",
            "f" to "MY",
            "fo" to "MZXQ",
            "foo" to "MZXW6",
            "foob" to "MZXW6YQ",
            "fooba" to "MZXW6YTB",
            "foobar" to "MZXW6YTBOI",
        )
        for ((plain, encoded) in vectors) {
            assertEquals("encoding $plain", encoded, Base32.encode(plain.toByteArray()))
            assertArrayEquals("decoding $encoded", plain.toByteArray(), Base32.decode(encoded))
        }
    }

    @Test
    fun `base32 tolerates padding and spacing from a copy-paste`() {
        assertArrayEquals("foobar".toByteArray(), Base32.decode("MZXW6YTBOI======"))
        assertArrayEquals("foobar".toByteArray(), Base32.decode("mzxw 6ytb oi"))
    }

    @Test
    fun `the provisioning uri carries what an authenticator app needs`() {
        val uri = Totp.provisioningUri("ABCDEFGHIJKLMNOP", "grady@example.com")
        assertTrue(uri, uri.startsWith("otpauth://totp/Kagua:grady%40example.com?"))
        assertTrue(uri, uri.contains("secret=ABCDEFGHIJKLMNOP"))
        assertTrue(uri, uri.contains("issuer=Kagua"))
        // SHA1 and 6 digits stated explicitly: apps that assume otherwise would show
        // codes this server rejects, which is the hardest kind of fault to diagnose.
        assertTrue(uri, uri.contains("algorithm=SHA1"))
        assertTrue(uri, uri.contains("digits=6"))
        assertTrue(uri, uri.contains("period=30"))
    }
}
