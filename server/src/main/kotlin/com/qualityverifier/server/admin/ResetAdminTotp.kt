package com.qualityverifier.server.admin

import com.qualityverifier.server.Config
import com.qualityverifier.server.Database
import kotlinx.coroutines.runBlocking

/**
 * Resets an admin's second factor from the box. The break-glass path.
 *
 * The portal can already do this, and that is the route to use: it needs another admin to
 * confirm their own password, and it records who did it. This exists for the case the
 * portal cannot cover — one admin, or every admin, having lost their authenticator, where
 * there is nobody left who can sign in to perform the reset.
 *
 * Requiring server access is the point. It is a higher bar than a portal password, and it
 * is the same bar that could read the database directly anyway, so it grants nothing new.
 *
 * Run as:
 *
 *     java -cp /opt/kagua/kagua.jar com.qualityverifier.server.admin.ResetAdminTotpKt \
 *          "grady@example.com"
 *
 * The password is untouched — this only replaces the TOTP secret. Anyone using it still
 * needs the account's password, so a reset alone is not a way in.
 */
fun main(args: Array<String>) {
    if (args.size != 1) {
        System.err.println("usage: ResetAdminTotp <email>")
        kotlin.system.exitProcess(2)
    }
    val email = args[0].trim()

    val config = Config.fromEnvironment()
    val databaseConfig = config.database
    if (databaseConfig == null) {
        System.err.println("No database configured: KAGUA_DB_PASSWORD is not set in this environment.")
        kotlin.system.exitProcess(1)
    }

    val database = Database(databaseConfig)
    try {
        val store = PostgresAdminStore(database.source)
        val result = runBlocking {
            val credentials = store.credentialsFor(email) ?: return@runBlocking null
            val secret = store.resetTotp(credentials.id) ?: return@runBlocking null
            // Same reasoning as the portal's reset: a remembered browser needs only the
            // password, so leaving one in place would let this account keep signing in
            // without ever enrolling the new secret.
            val forgotten = store.revokeTrustedDevices(credentials.id)
            store.audit(
                adminId = null,
                adminEmail = "bootstrap",
                action = "reset-2fa",
                target = email,
                detail = "from the server; forgot $forgotten remembered browser(s)",
                ip = null,
            )
            Triple(credentials.id, secret, forgotten)
        }
        if (result == null) {
            System.err.println("No enabled admin with that email.")
            kotlin.system.exitProcess(1)
        }
        val (id, secret, forgotten) = result
        println()
        println("Reset the second factor for $email ($id).")
        println("Forgot $forgotten remembered browser(s).")
        println()
        println("Add this to an authenticator app before signing in:")
        println("  secret: $secret")
        println("  uri:    ${Totp.provisioningUri(secret, email)}")
        println()
        println("The password is unchanged. Signing in needs both.")
    } finally {
        database.close()
    }
}
