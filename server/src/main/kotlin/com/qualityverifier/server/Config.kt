package com.qualityverifier.server

/**
 * Everything the service needs to start, read from the environment.
 *
 * Secrets are not read from Parameter Store by this process. The launcher script
 * fetches them with the AWS CLI that is already on the box and execs the JVM with
 * them in its environment — which keeps the AWS SDK, and its transitive weight, out
 * of a jar that otherwise does not need to talk to AWS at all.
 */
data class Config(
    val port: Int,
    val host: String,
    /** The commit this jar was built from. Reported by /healthz so we can tell. */
    val version: String,
    val database: DatabaseConfig?,
) {
    companion object {
        fun fromEnvironment(env: (String) -> String? = System::getenv): Config {
            val password = env("KAGUA_DB_PASSWORD")
            return Config(
                port = env("KAGUA_PORT")?.toIntOrNull() ?: 8080,
                // Loopback by design: nginx terminates TLS and is the only thing that
                // should ever reach this process. Binding 0.0.0.0 would expose it on
                // port 8080 to anything the security group lets through.
                host = env("KAGUA_HOST") ?: "127.0.0.1",
                version = env("KAGUA_VERSION") ?: "dev",
                // Absent rather than blank when there is no password: starting without a
                // database is a valid state for a health-check-only deployment, and it
                // should be visible in /healthz rather than crash on the first query.
                database = password?.takeIf { it.isNotBlank() }?.let {
                    DatabaseConfig(
                        url = env("KAGUA_DB_URL") ?: "jdbc:postgresql://127.0.0.1:5432/kagua",
                        user = env("KAGUA_DB_USER") ?: "kagua",
                        password = it,
                    )
                },
            )
        }
    }
}

data class DatabaseConfig(val url: String, val user: String, val password: String) {
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=***)"
}
