package com.qualityverifier.server.auth

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.auth0.jwt.algorithms.Algorithm
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Access tokens.
 *
 * Fifteen minutes, deliberately short. The point of a stateless token is that no
 * lookup is needed to accept it, which is also the reason it cannot be revoked — so
 * the window in which a stolen one is useful has to be small enough to live with,
 * and the refresh token is where revocation actually happens.
 *
 * HS256 with the key from Parameter Store. Asymmetric signing would let something
 * else verify without holding the signing key, and nothing else needs to: one
 * process issues and verifies these.
 */
class AccessTokens(
    signingKey: String,
    private val issuer: String = "kagua",
    private val audience: String = "kagua-app",
    private val lifetime: Duration = Duration.ofMinutes(15),
    private val now: () -> Instant = Instant::now,
) {
    private val algorithm: Algorithm = Algorithm.HMAC256(signingKey)

    val verifier: JWTVerifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun issue(userId: String): Issued {
        val issuedAt = now()
        val expiresAt = issuedAt.plus(lifetime)
        val token = JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withSubject(userId)
            .withIssuedAt(Date.from(issuedAt))
            .withExpiresAt(Date.from(expiresAt))
            .sign(algorithm)
        return Issued(token, expiresAt, lifetime.seconds)
    }

    data class Issued(val token: String, val expiresAt: Instant, val expiresInSeconds: Long)
}
