package com.qualityverifier.server.db

import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import javax.sql.DataSource

/**
 * The maker's own context: workshop, tools and goals.
 *
 * Three tables (`V14`) behind one profile, because they are answered as one setup flow and
 * read as one block — the tool list is only useful next to the goals, and neither is useful
 * without knowing what the maker actually builds.
 *
 * Read on the phone rather than on the server: the profile goes into the maker's opening
 * turn as plain language they can see, the same way the buyer's intake answers do. The
 * server holds it so it survives a reinstall and so it is part of the research record.
 */
interface FundiStore {
    suspend fun profileFor(userId: String): FundiProfile?

    /**
     * Replaces the whole profile.
     *
     * Whole-profile rather than per-field, because the setup flow answers all of it at
     * once and a partial write would leave the tool list half from one session and half
     * from another — which reads to the assistant as a maker who owns a strange mixture of
     * things. One transaction, so a failure leaves the previous answers intact.
     */
    suspend fun saveProfile(userId: String, profile: FundiProfile)
}

class PostgresFundiStore(private val dataSource: DataSource) : FundiStore {

    private suspend fun <T> tx(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = block(connection)
                connection.commit()
                result
            } catch (e: Throwable) {
                connection.rollback()
                throw e
            } finally {
                connection.autoCommit = true
            }
        }
    }

    override suspend fun profileFor(userId: String): FundiProfile? = tx { connection ->
        val workshop = connection.prepareStatement(
            """
            select works_at, years_in_trade, workers, makes,
                   pieces_per_month, usual_timber, rents_tools
              from fundi_workshops where user_id = ?::uuid
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                if (!rows.next()) null else Workshop(
                    worksAt = rows.getString(1),
                    yearsInTrade = rows.getInt(2).takeUnless { rows.wasNull() },
                    workers = rows.getInt(3).takeUnless { rows.wasNull() },
                    makes = rows.getString(4),
                    piecesPerMonth = rows.getInt(5).takeUnless { rows.wasNull() },
                    usualTimber = rows.getString(6),
                    rentsTools = rows.getBoolean(7),
                )
            }
        }
        // No workshop row means no profile at all, not an empty one. The distinction is
        // what tells a freshly registered fundi from one who answered nothing.
        if (workshop == null) return@tx null

        val tools = connection.prepareStatement(
            "select kind, ownership, day_rate_kes from fundi_tools where user_id = ?::uuid"
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<OwnedTool>()
                while (rows.next()) {
                    // A row this build does not recognise is dropped rather than thrown on.
                    // The CHECK and ToolKind are kept in step by FundiVocabularyTest, so
                    // this is only reachable across a downgrade — where losing one tool
                    // beats losing the profile.
                    val kind = ToolKind.fromId(rows.getString(1)) ?: continue
                    val ownership = ToolOwnership.fromId(rows.getString(2)) ?: continue
                    out += OwnedTool(
                        kind = kind,
                        ownership = ownership,
                        dayRateKes = rows.getInt(3).takeUnless { rows.wasNull() },
                    )
                }
                out
            }
        }

        val goals = connection.prepareStatement(
            "select goal from fundi_goals where user_id = ?::uuid"
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                val out = mutableSetOf<FundiGoal>()
                while (rows.next()) FundiGoal.fromId(rows.getString(1))?.let { out += it }
                out
            }
        }

        FundiProfile(workshop = workshop, tools = tools, goals = goals)
    }

    override suspend fun saveProfile(userId: String, profile: FundiProfile): Unit = tx { connection ->
        connection.prepareStatement(
            """
            insert into fundi_workshops (
                user_id, works_at, years_in_trade, workers, makes,
                pieces_per_month, usual_timber, rents_tools, updated_at
            ) values (?::uuid, ?, ?, ?, ?, ?, ?, ?, now())
            on conflict (user_id) do update set
                works_at = excluded.works_at,
                years_in_trade = excluded.years_in_trade,
                workers = excluded.workers,
                makes = excluded.makes,
                pieces_per_month = excluded.pieces_per_month,
                usual_timber = excluded.usual_timber,
                rents_tools = excluded.rents_tools,
                updated_at = now()
            """.trimIndent()
        ).use { statement ->
            val workshop = profile.workshop
            statement.setString(1, userId)
            statement.setString(2, workshop.worksAt?.trimToNull())
            statement.setNullableInt(3, workshop.yearsInTrade)
            statement.setNullableInt(4, workshop.workers)
            statement.setString(5, workshop.makes?.trimToNull())
            statement.setNullableInt(6, workshop.piecesPerMonth)
            statement.setString(7, workshop.usualTimber?.trimToNull())
            statement.setBoolean(8, workshop.rentsTools)
            statement.executeUpdate()
        }

        // Deleted and rewritten rather than merged. A tool the maker removed has to
        // disappear, and an upsert alone would leave it behind — which would tell the
        // coaching they still own something they sold.
        connection.prepareStatement("delete from fundi_tools where user_id = ?::uuid")
            .use { it.setString(1, userId); it.executeUpdate() }
        connection.prepareStatement(
            """
            insert into fundi_tools (user_id, kind, ownership, day_rate_kes)
            values (?::uuid, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            profile.tools.distinctBy { it.kind }.forEach { tool ->
                statement.setString(1, userId)
                statement.setString(2, tool.kind.id)
                statement.setString(3, tool.ownership.id)
                // The CHECK refuses a rate of zero or less; a nonsense figure becomes no
                // figure rather than a rejected profile.
                statement.setNullableInt(4, tool.dayRateKes?.takeIf { it > 0 })
                statement.addBatch()
            }
            statement.executeBatch()
        }

        connection.prepareStatement("delete from fundi_goals where user_id = ?::uuid")
            .use { it.setString(1, userId); it.executeUpdate() }
        connection.prepareStatement(
            "insert into fundi_goals (user_id, goal) values (?::uuid, ?)"
        ).use { statement ->
            profile.goals.forEach { goal ->
                statement.setString(1, userId)
                statement.setString(2, goal.id)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }
}

private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}
