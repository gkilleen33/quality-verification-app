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
    /** Absent means auth is not mounted. See Application.main. */
    val jwtSigningKey: String?,
    /** Absent means the chat routes are not mounted. */
    val anthropicApiKey: String?,
    /** Photos and the prompt cache live here. The only writable path in the unit. */
    val dataDirectory: String,
    val promptBaseUrl: String,
    /**
     * Assessments one account may start per day, in East African time.
     *
     * Phase 2 moved the bill from the tester's own API key to ours, so an account in a
     * loop is now our invoice rather than their problem. Zero or less means no limit,
     * which is a deliberate escape hatch for a demo rather than a default.
     */
    val dailyAssessmentLimit: Int,
) {
    companion object {
        /**
         * Twenty. Comfortable for a full day of fieldwork in a workshop, while bounding
         * what a runaway client can spend before somebody notices.
         */
        const val DEFAULT_DAILY_ASSESSMENT_LIMIT = 20

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
                jwtSigningKey = env("KAGUA_JWT_SIGNING_KEY")?.takeIf { it.isNotBlank() },
                anthropicApiKey = env("KAGUA_ANTHROPIC_API_KEY")?.takeIf { it.isNotBlank() },
                dataDirectory = env("KAGUA_DATA_DIR") ?: "/var/lib/kagua",
                // Same source the phone reads, so a prompt change still lands by pushing
                // to main rather than by shipping anything.
                promptBaseUrl = env("KAGUA_PROMPT_BASE_URL")
                    ?: "https://raw.githubusercontent.com/gkilleen33/quality-verification-app/main/prompts/",
                // Counted per calendar day rather than as a rolling window, so the answer
                // to "when can I carry on" is "tomorrow" rather than a timestamp a user
                // has to work out.
                dailyAssessmentLimit = env("KAGUA_DAILY_ASSESSMENT_LIMIT")?.toIntOrNull()
                    ?: DEFAULT_DAILY_ASSESSMENT_LIMIT,
            )
        }
    }
}

data class DatabaseConfig(val url: String, val user: String, val password: String) {
    override fun toString(): String = "DatabaseConfig(url=$url, user=$user, password=***)"
}
