package com.qualityverifier.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The health endpoints, and the distinction between them.
 *
 * /healthz must not depend on the database. If it did, a Postgres restart would make
 * the service look dead to whatever is watching it, and somebody would restart the
 * wrong thing.
 */
class HealthTest {

    @Test
    fun `healthz is ok with no database at all`() = testApplication {
        application { module(version = "test-sha", database = null) }

        val response = client.get("/healthz")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body, body.contains("\"status\":\"ok\""))
        assertTrue("the running build should be identifiable", body.contains("test-sha"))
    }

    @Test
    fun `deep health reports degraded rather than ok when there is no database`() = testApplication {
        application { module(version = "test-sha", database = null) }

        val response = client.get("/healthz/deep")

        // 503 on purpose: a monitor that only looks at the status code must not read
        // "no database configured" as healthy.
        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        assertTrue(response.bodyAsText().contains("not configured"))
    }

    @Test
    fun `deep health reports what the database said`() = testApplication {
        application {
            module(
                version = "test-sha",
                database = DatabaseHealth { Result.success("kagua @ V1,V2,V3") },
            )
        }

        val response = client.get("/healthz/deep")

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body, body.contains("V1,V2,V3"))
    }

    @Test
    fun `a database failure is degraded, and does not leak the exception message`() = testApplication {
        application {
            module(
                version = "test-sha",
                database = DatabaseHealth {
                    Result.failure(IllegalStateException("password authentication failed for user kagua"))
                },
            )
        }

        val response = client.get("/healthz/deep")
        val body = response.bodyAsText()

        assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        // The class name is useful and harmless; the message could name a user or a host.
        assertTrue(body, body.contains("IllegalStateException"))
        assertTrue("the exception message must not reach the wire", !body.contains("password"))
    }

    @Test
    fun `config keeps the service on loopback and hides the password`() {
        val env = mapOf(
            "KAGUA_DB_PASSWORD" to "hunter2",
            "KAGUA_VERSION" to "abc1234",
        )
        val config = Config.fromEnvironment(env::get)

        assertEquals("127.0.0.1", config.host)
        assertEquals(8080, config.port)
        assertEquals("abc1234", config.version)
        assertEquals("kagua", config.database?.user)
        // toString lands in logs and crash reports by accident all the time.
        assertTrue(config.database.toString(), !config.database.toString().contains("hunter2"))
    }

    @Test
    fun `no password means no database rather than a broken one`() {
        val config = Config.fromEnvironment(mapOf("KAGUA_DB_PASSWORD" to "  ")::get)
        assertEquals(null, config.database)
    }
}
