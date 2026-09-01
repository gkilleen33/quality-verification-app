package com.qualityverifier.server

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/**
 * What /healthz needs to know about the database, so the route needs no JDBC.
 *
 * A `fun interface` so a test can pass a lambda instead of standing up Postgres — the
 * route's job is turning a Result into a status code, and that is worth testing on its
 * own.
 */
fun interface DatabaseHealth {
    suspend fun check(): Result<String>
}

/**
 * The connection pool.
 *
 * Ten connections against Postgres' max_connections of 40, on a box with two vCPUs:
 * a pool larger than the cores it is served by adds context switching, not
 * throughput. The server spends its time waiting on Anthropic, not on Postgres.
 */
class Database(config: DatabaseConfig) : DatabaseHealth, AutoCloseable {

    private val dataSource: HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.url
            username = config.user
            password = config.password
            maximumPoolSize = 10
            minimumIdle = 2
            // Fail fast. A request that cannot get a connection in five seconds should
            // say so, not sit in a queue behind a stuck pool while the phone waits.
            connectionTimeout = 5_000
            poolName = "kagua"
        }
    )

    val source: DataSource get() = dataSource

    override suspend fun check(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select current_database() || ' @ ' || " +
                            "(select string_agg(version, ',' order by version) from schema_migrations)"
                    ).use { rows ->
                        if (rows.next()) rows.getString(1) else "no rows"
                    }
                }
            }
        }
    }

    override fun close() = dataSource.close()
}
