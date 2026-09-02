package com.qualityverifier.server.admin

import com.qualityverifier.server.Config
import com.qualityverifier.server.Database
import com.qualityverifier.server.auth.Passwords
import kotlinx.coroutines.runBlocking

/**
 * Creates the first admin, from the box.
 *
 * There is no self-registration on the portal — an open sign-up form on a page that can
 * read every customer's conversation would be the whole security model undone — so the
 * first account has to come from somewhere with server access. After that, admins add each
 * other from inside.
 *
 * Run as:
 *
 *     java -cp /opt/kagua/kagua.jar com.qualityverifier.server.admin.CreateAdminKt \
 *          "grady@example.com" "Grady Killeen"
 *
 * The password is read from stdin, not from an argument. An argument would land in the
 * shell history, in `ps` output, and — because this is run over SSM — in an AWS command
 * log that keeps its parameters.
 */
fun main(args: Array<String>) {
    if (args.size != 2) {
        System.err.println("usage: CreateAdmin <email> <name>   (password on stdin)")
        kotlin.system.exitProcess(2)
    }
    val email = args[0].trim()
    val name = args[1].trim()

    val password = readPassword()
    if (password.length < 12) {
        System.err.println("A password of at least 12 characters, please.")
        kotlin.system.exitProcess(2)
    }

    val config = Config.fromEnvironment()
    val databaseConfig = config.database
    if (databaseConfig == null) {
        System.err.println("No database configured: KAGUA_DB_PASSWORD is not set in this environment.")
        kotlin.system.exitProcess(1)
    }

    val database = Database(databaseConfig)
    try {
        val store = PostgresAdminStore(database.source)
        val created = runBlocking {
            store.createAdmin(email, name, Passwords.hash(password), createdBy = null)
        }
        if (created == null) {
            System.err.println("There is already an admin with that email.")
            kotlin.system.exitProcess(1)
        }
        val (id, secret) = created
        runBlocking {
            store.audit(null, "bootstrap", "create-admin", target = email, detail = "from the server", ip = null)
        }
        println()
        println("Created $email ($id).")
        println()
        println("Add this to an authenticator app before signing in:")
        println("  secret: $secret")
        println("  uri:    ${Totp.provisioningUri(secret, email)}")
        println()
        println("The account cannot sign in until a code from that app has been accepted,")
        println("so an unfinished enrolment leaves no usable account behind.")
    } finally {
        database.close()
    }
}

/**
 * Reads the password without echoing it where possible.
 *
 * System.console() is null when stdin is a pipe, which is how SSM runs a command — so a
 * fallback that reads a line is not optional. It does mean the password is visible if
 * somebody is watching an interactive terminal, which is worth saying out loud rather than
 * pretending the console path always applies.
 */
private fun readPassword(): String {
    val console = System.console()
    if (console != null) {
        print("Password: ")
        return String(console.readPassword())
    }
    System.err.println("(stdin is not a terminal; the password will not be hidden)")
    return readlnOrNull().orEmpty()
}
