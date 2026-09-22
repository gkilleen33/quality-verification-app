package com.qualityverifier.server.db

import com.qualityverifier.domain.FundiGoal
import com.qualityverifier.domain.FundiProfile
import com.qualityverifier.domain.OwnedTool
import com.qualityverifier.domain.ToolChange
import com.qualityverifier.domain.ToolChangeReason
import com.qualityverifier.domain.ToolKind
import com.qualityverifier.domain.ToolOwnership
import com.qualityverifier.domain.Workshop
import com.qualityverifier.domain.toolChanges
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
     * Records the maker's answers.
     *
     * One transaction, so a failure leaves the previous answers intact.
     *
     * **Tools are never deleted.** Each answered tool is upserted and every transition is
     * appended to `fundi_tool_changes`, because a shop that owned a circular saw last
     * March and does not now has told us something — and a maker who bought a marking
     * gauge after a fix plan named its absence is the clearest evidence the coaching did
     * anything. A tool absent from [profile] is left exactly as it was: nobody said
     * anything about it, and silence is not disposal. See [toolChanges], which is where
     * that rule actually lives.
     */
    suspend fun saveProfile(userId: String, profile: FundiProfile)

    /** A maker's tool transitions, newest first. For the record rather than the coaching. */
    suspend fun toolHistory(userId: String): List<RecordedToolChange>
}

/** A stored [ToolChange], with when it was recorded. */
data class RecordedToolChange(val change: ToolChange, val changedAtMillis: Long)

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

        val tools = readTools(connection, userId)

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

        // Read inside the same transaction as the write, so a second save arriving while
        // this one is open cannot make both of them think they were the change.
        val previous = readTools(connection, userId)
        val changes = toolChanges(previous, profile.tools)

        // History first. If the upsert below fails, the transaction takes this with it —
        // the two must not be able to disagree about what a maker owns.
        if (changes.isNotEmpty()) {
            connection.prepareStatement(
                """
                insert into fundi_tool_changes
                    (user_id, kind, from_ownership, to_ownership, reason, note)
                values (?::uuid, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                changes.forEach { change ->
                    statement.setString(1, userId)
                    statement.setString(2, change.kind.id)
                    statement.setString(3, change.from?.id)
                    statement.setString(4, change.to.id)
                    statement.setString(5, change.reason?.id)
                    statement.setString(6, change.note)
                    statement.addBatch()
                }
                statement.executeBatch()
            }
        }

        // Upserted, never deleted. A tool the maker did not mention keeps the answer it
        // had: silence is not disposal, and deleting the row would destroy the one record
        // that says they ever had it.
        connection.prepareStatement(
            """
            insert into fundi_tools (user_id, kind, ownership, day_rate_kes)
            values (?::uuid, ?, ?, ?)
            on conflict (user_id, kind) do update set
                ownership = excluded.ownership,
                day_rate_kes = excluded.day_rate_kes
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

    override suspend fun toolHistory(userId: String): List<RecordedToolChange> = tx { connection ->
        connection.prepareStatement(
            """
            select kind, from_ownership, to_ownership, reason, note, changed_at
              from fundi_tool_changes
             where user_id = ?::uuid
             order by changed_at desc, id desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<RecordedToolChange>()
                while (rows.next()) {
                    val kind = ToolKind.fromId(rows.getString(1)) ?: continue
                    val to = ToolOwnership.fromId(rows.getString(3)) ?: continue
                    out += RecordedToolChange(
                        change = ToolChange(
                            kind = kind,
                            from = rows.getString(2)?.let(ToolOwnership::fromId),
                            to = to,
                            reason = rows.getString(4)?.let(ToolChangeReason::fromId),
                            note = rows.getString(5),
                        ),
                        changedAtMillis = rows.getTimestamp(6).time,
                    )
                }
                out
            }
        }
    }
}

/**
 * Current tool state, as stored.
 *
 * Shared by the read and the save, because the save has to compare against exactly what
 * the read would have returned — two slightly different queries here would mean the
 * history disagreed with the profile.
 *
 * A row this build does not recognise is dropped rather than thrown on. `ToolKind` and the
 * CHECK are kept in step by `FundiVocabularyTest`, so this is only reachable across a
 * downgrade, where losing one tool beats losing the profile.
 */
private fun readTools(connection: Connection, userId: String): List<OwnedTool> =
    connection.prepareStatement(
        "select kind, ownership, day_rate_kes from fundi_tools where user_id = ?::uuid"
    ).use { statement ->
        statement.setString(1, userId)
        statement.executeQuery().use { rows ->
            val out = mutableListOf<OwnedTool>()
            while (rows.next()) {
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

private fun String.trimToNull(): String? = trim().takeIf { it.isNotEmpty() }

private fun PreparedStatement.setNullableInt(index: Int, value: Int?) {
    if (value == null) setNull(index, Types.INTEGER) else setInt(index, value)
}
