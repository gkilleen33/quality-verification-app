package com.qualityverifier.server.db

import com.qualityverifier.domain.Employment
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

    /**
     * The one GPS point of the maker's shop, taken with their consent while they stood in
     * it. Written to the same three columns as a Kagua business's sign-up point
     * (`users.business_location`, V2), which account deletion already clears (V11).
     * A later point replaces an earlier one: a shop that moved is at its new address.
     */
    suspend fun saveWorkshopLocation(userId: String, latitude: Double, longitude: Double, accuracyMetres: Double)

    /**
     * Names a piece, or clears its name with null. Returns false when the piece is
     * not this account's, which the route answers as 404.
     *
     * The server stamps nothing: the phone's clock is not trusted for ordering (see
     * V18), and the last write the server receives is the name.
     */
    suspend fun renamePiece(userId: String, pieceId: String, label: String?): Boolean

    /** The roster, archived names included, so a reinstalled phone keeps them on past pieces. */
    suspend fun listWorkers(userId: String): List<WorkerRow>

    /**
     * Adds or renames a worker. Returns false when the id belongs to another account.
     *
     * Archiving is sticky: the first `archived = true` sets `archived_at`, and nothing
     * clears it, so a stale rename from a second phone cannot revive a worker the shop
     * has let go. The server stamps `updated_at` from its own clock (V18).
     *
     * The number and the kind are optional on the wire, so an app from before V30 keeps
     * working: [phone] says whether the request named a number at all (see
     * [PhoneChange]), and a null [employment] leaves the stored kind, or makes a new
     * entry an employee. Both follow the name's rule otherwise: the last write received
     * wins.
     */
    suspend fun upsertWorker(
        userId: String,
        workerId: String,
        name: String,
        archived: Boolean,
        phone: PhoneChange = PhoneChange.Keep,
        employment: Employment? = null,
    ): Boolean

    /** The studios this account says it works at (V31), archived ones included. */
    suspend fun listStudios(userId: String): List<StudioRow>

    /**
     * Adds or edits one studio entry. Returns false when the id belongs to another account.
     * Archiving is sticky, as for a worker. [phone] is E.164, checked by the route. The
     * link record (V31) is brought up to date in the same transaction.
     */
    suspend fun upsertStudio(userId: String, studioId: String, name: String, phone: String, archived: Boolean): Boolean

    /**
     * Labels a piece with a studio after it was created (V34): [studioEntryId] is an entry
     * of the maker's own list, or null for "My own job". A piece never asked takes its first
     * answer; a piece already answered takes one correction, and no more. Each change is
     * recorded in `piece_studio_changes` with the old and new values, in the same
     * transaction.
     */
    suspend fun setPieceStudio(userId: String, pieceId: String, studioEntryId: String?): PieceStudioChange

    /**
     * Records the fundi's answer to "Is this piece finished?" (V44, row 55): [stage] is one
     * of `PieceStage.ids`. Only the piece's maker. Each answer, a repeat included, is a row in
     * `piece_stage_changes` with the old and new stage, in the same transaction, and moves
     * `stage_set_at` to now, so the newest answer is the one that shows.
     */
    suspend fun setPieceStage(userId: String, pieceId: String, stage: String): PieceStageChange

    /** Today's answer to "Which studio are you at today?" (V34), or null when not yet answered. */
    suspend fun studioToday(userId: String): StudioDay?

    /**
     * Records today's answer, once per Nairobi day: a second answer the same day keeps the
     * first and returns it. [studioEntryId] is an entry of the account's own list, or null
     * for its own workshop; another account's entry is [StudioDayAnswer.NotYourStudio].
     */
    suspend fun answerStudioToday(userId: String, studioEntryId: String?): StudioDayAnswer
}

/** What a label change came to. */
sealed interface PieceStudioChange {
    data object Changed : PieceStudioChange
    /** The label already says this: nothing written, and the correction is not spent. */
    data object Unchanged : PieceStudioChange
    /** Not this account's piece, or none: the route answers 404 either way. */
    data object NoSuchPiece : PieceStudioChange
    /** The entry is not on this account's own list. */
    data object NotYourStudio : PieceStudioChange
    /** The one correction has been used. */
    data object AlreadyCorrected : PieceStudioChange
}

/** What setting a piece's stage came to (V44, row 55). */
sealed interface PieceStageChange {
    /** Stored; [setAt] is the server's time, epoch millis. */
    data class Set(val stage: String, val setAt: Long) : PieceStageChange

    /** Not this account's piece, or none: the route answers 404 either way. */
    data object NoSuchPiece : PieceStageChange
}

/** One account's answer for one Nairobi day. [studioEntryId] null is its own workshop. */
data class StudioDay(val day: String, val studioEntryId: String?)

sealed interface StudioDayAnswer {
    data class Recorded(val day: StudioDay) : StudioDayAnswer
    data object NotYourStudio : StudioDayAnswer
}

/**
 * A studio entry as the phone reads it back. [linked] is the two-sided match: true only
 * when the studio's roster names this account's number too, so an unlinked entry reads the
 * same whether or not its number has an account.
 */
data class StudioRow(
    val id: String,
    val name: String?,
    val phone: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    val linked: Boolean,
)

/**
 * What a roster write says about the worker's number.
 *
 * Three answers, because "absent" and "remove" must differ: an app from before V30 never
 * sends the field, and reading that as "remove" would clear every number the moment a
 * manager renamed a worker from an old phone. A new app sends the number it holds on every
 * write, with blank for none, so a number removed on the phone is removed here.
 */
sealed interface PhoneChange {
    /** The field was absent (or null): the stored number stays as it is. */
    data object Keep : PhoneChange

    /** The field was blank: the stored number is cleared. */
    data object Remove : PhoneChange

    /** A number in E.164, already normalised and checked by the route. */
    data class Set(val e164: String) : PhoneChange
}

/** A roster row as the phone reads it back. Times in epoch millis. */
data class WorkerRow(
    val id: String,
    val name: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val archivedAt: Long?,
    /** E.164, or null for an entry without a number. */
    val phone: String? = null,
    val employment: Employment = Employment.EMPLOYEE,
    /** The two-sided match (V31): the worker's own account lists this workshop too. */
    val linked: Boolean = false,
)

/** The one rule for a piece's name, shared by the turn and the rename endpoint. */
object PieceLabels {
    /** Matches V22's `pieces_label_length`, counted in characters as Postgres counts them. */
    const val MAX_LENGTH = 80

    fun length(label: String): Int = label.codePointCount(0, label.length)

    /**
     * Trimmed; null when blank, or when it holds a NUL, which Postgres text cannot store
     * and would otherwise fail the whole statement with error 22021.
     */
    fun clean(label: String?): String? =
        label?.trim()?.takeIf { it.isNotEmpty() && '\u0000' !in it }

    /** [clean], and null when too long, so the row is never refused over a name. */
    fun normalise(label: String?): String? =
        clean(label)?.takeIf { length(it) <= MAX_LENGTH }
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

    override suspend fun saveWorkshopLocation(
        userId: String,
        latitude: Double,
        longitude: Double,
        accuracyMetres: Double,
    ): Unit = tx { connection ->
        connection.prepareStatement(
            """
            update users set
                business_location = ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography,
                business_location_accuracy_m = ?,
                business_location_at = now()
            where id = ?::uuid
            """.trimIndent()
        ).use { statement ->
            // Longitude first: ST_MakePoint takes x then y.
            statement.setDouble(1, longitude)
            statement.setDouble(2, latitude)
            statement.setDouble(3, accuracyMetres)
            statement.setString(4, userId)
            statement.executeUpdate()
        }
        Unit
    }

    override suspend fun renamePiece(userId: String, pieceId: String, label: String?): Boolean =
        tx { connection ->
            connection.prepareStatement(
                "update pieces set label = ? where id = ?::uuid and user_id = ?::uuid"
            ).use { statement ->
                if (label == null) statement.setNull(1, Types.VARCHAR) else statement.setString(1, label)
                statement.setString(2, pieceId)
                statement.setString(3, userId)
                // Zero rows means not theirs, or no such piece: the same answer either way.
                statement.executeUpdate() > 0
            }
        }

    override suspend fun listWorkers(userId: String): List<WorkerRow> = tx { connection ->
        connection.prepareStatement(
            """
            select w.id::text, w.name,
                   (extract(epoch from w.created_at) * 1000)::bigint,
                   (extract(epoch from w.updated_at) * 1000)::bigint,
                   (extract(epoch from w.archived_at) * 1000)::bigint,
                   w.phone, w.employment,
                   -- Linked when the match (worker_links, V31) holds a pair for this
                   -- roster's account and the Fundi Bora account the entry's number
                   -- belongs to (a Kagua account on the same number never is, V38). By
                   -- the pair rather than the entry id, so a second entry for one number
                   -- reads as linked too.
                   (w.archived_at is null and w.phone is not null and exists (
                       select 1 from worker_links l join users u on u.id = l.worker_user_id
                        where l.studio_user_id = w.user_id and u.phone = w.phone
                          and u.audience = 'fundi'
                   ))
            from workers w where w.user_id = ?::uuid order by w.created_at, w.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<WorkerRow>()
                while (rows.next()) {
                    out += WorkerRow(
                        id = rows.getString(1),
                        name = rows.getString(2),
                        createdAt = rows.getLong(3),
                        updatedAt = rows.getLong(4),
                        archivedAt = rows.getLong(5).takeUnless { rows.wasNull() },
                        phone = rows.getString(6),
                        // The CHECK and the enum are held together by FundiVocabularyTest,
                        // so an unknown kind is only reachable across a downgrade.
                        employment = Employment.fromId(rows.getString(7)) ?: Employment.EMPLOYEE,
                        linked = rows.getBoolean(8),
                    )
                }
                out
            }
        }
    }

    override suspend fun listStudios(userId: String): List<StudioRow> = tx { connection ->
        connection.prepareStatement(
            """
            select ws.id::text, ws.name, ws.phone,
                   (extract(epoch from ws.created_at) * 1000)::bigint,
                   (extract(epoch from ws.updated_at) * 1000)::bigint,
                   (extract(epoch from ws.archived_at) * 1000)::bigint,
                   -- Linked when the match holds a pair for this account and the Fundi
                   -- Bora account the entry's number belongs to: the studio's roster names
                   -- us too. A Kagua account on the same number never is (V38).
                   (ws.archived_at is null and ws.phone is not null and exists (
                       select 1 from worker_links l join users s on s.id = l.studio_user_id
                        where l.worker_user_id = ws.user_id and s.phone = ws.phone
                          and s.audience = 'fundi'
                   ))
            from worker_studios ws where ws.user_id = ?::uuid order by ws.created_at, ws.id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<StudioRow>()
                while (rows.next()) {
                    out += StudioRow(
                        id = rows.getString(1),
                        name = rows.getString(2),
                        phone = rows.getString(3),
                        createdAt = rows.getLong(4),
                        updatedAt = rows.getLong(5),
                        archivedAt = rows.getLong(6).takeUnless { rows.wasNull() },
                        linked = rows.getBoolean(7),
                    )
                }
                out
            }
        }
    }

    override suspend fun upsertStudio(
        userId: String,
        studioId: String,
        name: String,
        phone: String,
        archived: Boolean,
    ): Boolean = tx { connection ->
        val written = connection.prepareStatement(
            """
            insert into worker_studios (id, user_id, name, phone, archived_at)
            values (?::uuid, ?::uuid, ?, ?, case when ? then now() end)
            on conflict (id) do update
                set name        = excluded.name,
                    phone       = excluded.phone,
                    updated_at  = now(),
                    -- Sticky: once archived, a later write cannot revive it.
                    archived_at = coalesce(worker_studios.archived_at, excluded.archived_at)
                -- Another account's id: no row changes, and the caller answers 404.
                where worker_studios.user_id = excluded.user_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, studioId)
            statement.setString(2, userId)
            statement.setString(3, name)
            statement.setString(4, phone)
            statement.setBoolean(5, archived)
            statement.executeUpdate() > 0
        }
        if (written) recordLinkChanges(connection)
        written
    }

    /**
     * Brings the link record (V31's worker_link_events) up to date with the match, in the
     * caller's transaction, so an event follows every write that can make or end a link.
     * Idempotent: writes nothing when nothing changed.
     */
    private fun recordLinkChanges(connection: Connection) {
        connection.prepareStatement("select sync_worker_link_events()").use { statement ->
            statement.executeQuery().use { it.next() }
        }
    }

    override suspend fun upsertWorker(
        userId: String,
        workerId: String,
        name: String,
        archived: Boolean,
        phone: PhoneChange,
        employment: Employment?,
    ): Boolean = tx { connection ->
        connection.prepareStatement(
            """
            insert into workers (id, user_id, name, archived_at, phone, employment)
            values (?::uuid, ?::uuid, ?, case when ? then now() end, ?, coalesce(?, 'employee'))
            on conflict (id) do update
                set name        = excluded.name,
                    -- Keep: the request said nothing about the number (an app from before
                    -- V30). Otherwise the number sent, which is null for Remove.
                    phone       = case when ? then workers.phone else excluded.phone end,
                    -- Null leaves the kind as it was, for the same reason.
                    employment  = coalesce(?, workers.employment),
                    updated_at  = now(),
                    -- Sticky: once archived, a later write cannot revive it.
                    archived_at = coalesce(workers.archived_at, excluded.archived_at)
                -- Another account's id: no row changes, and the caller answers 404.
                where workers.user_id = excluded.user_id
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, workerId)
            statement.setString(2, userId)
            statement.setString(3, name)
            statement.setBoolean(4, archived)
            val number = (phone as? PhoneChange.Set)?.e164
            if (number == null) statement.setNull(5, Types.VARCHAR) else statement.setString(5, number)
            val kind = employment?.id
            if (kind == null) statement.setNull(6, Types.VARCHAR) else statement.setString(6, kind)
            statement.setBoolean(7, phone == PhoneChange.Keep)
            if (kind == null) statement.setNull(8, Types.VARCHAR) else statement.setString(8, kind)
            statement.executeUpdate() > 0
        }.also { written ->
            // A number set, changed, removed, or an entry archived can make or end a link.
            if (written) recordLinkChanges(connection)
        }
    }

    override suspend fun setPieceStudio(userId: String, pieceId: String, studioEntryId: String?): PieceStudioChange =
        tx { connection ->
            // Locked, so two corrections sent at once cannot both read "not yet corrected".
            // The unique index would refuse the second anyway; this makes it an answer
            // rather than a constraint violation.
            val current = connection.prepareStatement(
                """
                select studio_entry_id::text, studio_chosen_at is not null
                  from pieces where id = ?::uuid and user_id = ?::uuid
                   for update
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, pieceId)
                statement.setString(2, userId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getString(1) to rows.getBoolean(2) else null
                }
            } ?: return@tx PieceStudioChange.NoSuchPiece
            val (oldEntry, chosen) = current

            if (studioEntryId != null && !ownsLiveStudioEntry(connection, userId, studioEntryId)) {
                return@tx PieceStudioChange.NotYourStudio
            }
            if (chosen && oldEntry == studioEntryId) return@tx PieceStudioChange.Unchanged

            val kind = if (chosen) "correction" else "first_choice"
            if (chosen) {
                val corrected = connection.prepareStatement(
                    "select 1 from piece_studio_changes where piece_id = ?::uuid and kind = 'correction'"
                ).use { statement ->
                    statement.setString(1, pieceId)
                    statement.executeQuery().use { it.next() }
                }
                if (corrected) return@tx PieceStudioChange.AlreadyCorrected
            }
            connection.prepareStatement(
                """
                insert into piece_studio_changes (piece_id, user_id, kind, old_studio_entry_id, new_studio_entry_id)
                values (?::uuid, ?::uuid, ?, ?::uuid, ?::uuid)
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, pieceId)
                statement.setString(2, userId)
                statement.setString(3, kind)
                statement.setString(4, oldEntry)
                statement.setString(5, studioEntryId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                update pieces set studio_entry_id = ?::uuid,
                                  studio_chosen_at = coalesce(studio_chosen_at, now())
                 where id = ?::uuid
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, studioEntryId)
                statement.setString(2, pieceId)
                statement.executeUpdate()
            }
            PieceStudioChange.Changed
        }

    override suspend fun setPieceStage(userId: String, pieceId: String, stage: String): PieceStageChange =
        tx { connection ->
            // Locked, so two answers sent at once are recorded in order, each with the old
            // stage the other left. Only the maker's own piece: a manager's is not found.
            val old = connection.prepareStatement(
                "select stage from pieces where id = ?::uuid and user_id = ?::uuid for update"
            ).use { statement ->
                statement.setString(1, pieceId)
                statement.setString(2, userId)
                statement.executeQuery().use { rows -> if (rows.next()) listOf(rows.getString(1)) else null }
            } ?: return@tx PieceStageChange.NoSuchPiece
            connection.prepareStatement(
                "insert into piece_stage_changes (piece_id, user_id, old_stage, new_stage) values (?::uuid, ?::uuid, ?, ?)"
            ).use { statement ->
                statement.setString(1, pieceId)
                statement.setString(2, userId)
                statement.setString(3, old.single())
                statement.setString(4, stage)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                update pieces set stage = ?, stage_set_at = now()
                 where id = ?::uuid
                returning (extract(epoch from stage_set_at) * 1000)::bigint
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, stage)
                statement.setString(2, pieceId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    PieceStageChange.Set(stage, rows.getLong(1))
                }
            }
        }

    /**
     * Whether [entryId] is a live entry of [userId]'s own list (V31). An archived one is a
     * studio they no longer work for: no label and no day's answer may name it.
     */
    private fun ownsLiveStudioEntry(connection: Connection, userId: String, entryId: String): Boolean =
        connection.prepareStatement(
            "select 1 from worker_studios where id = ?::uuid and user_id = ?::uuid and archived_at is null"
        ).use { statement ->
            statement.setString(1, entryId)
            statement.setString(2, userId)
            statement.executeQuery().use { it.next() }
        }

    override suspend fun studioToday(userId: String): StudioDay? = tx { connection ->
        connection.prepareStatement(
            """
            select day::text, studio_entry_id::text from fundi_studio_days
             where user_id = ?::uuid and day = (now() at time zone 'Africa/Nairobi')::date
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                if (rows.next()) StudioDay(rows.getString(1), rows.getString(2)) else null
            }
        }
    }

    override suspend fun answerStudioToday(userId: String, studioEntryId: String?): StudioDayAnswer =
        tx { connection ->
            if (studioEntryId != null && !ownsLiveStudioEntry(connection, userId, studioEntryId)) {
                return@tx StudioDayAnswer.NotYourStudio
            }
            // The Nairobi day, as the daily allowance counts it. The first answer stands: a
            // phone that resends after a lost response must not move the day's answer.
            connection.prepareStatement(
                """
                insert into fundi_studio_days (user_id, day, studio_entry_id)
                values (?::uuid, (now() at time zone 'Africa/Nairobi')::date, ?::uuid)
                on conflict (user_id, day) do nothing
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.setString(2, studioEntryId)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                """
                select day::text, studio_entry_id::text from fundi_studio_days
                 where user_id = ?::uuid and day = (now() at time zone 'Africa/Nairobi')::date
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, userId)
                statement.executeQuery().use { rows ->
                    rows.next()
                    StudioDayAnswer.Recorded(StudioDay(rows.getString(1), rows.getString(2)))
                }
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
internal fun readTools(connection: Connection, userId: String): List<OwnedTool> =
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
