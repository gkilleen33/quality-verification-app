package com.qualityverifier.server.api

import com.qualityverifier.domain.FundiIntake
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * Reads for the data API.
 *
 * Its own queries rather than AdminStore's, for the same reason its DTOs are its own: the
 * portal's shape follows what a page needs to draw, and a published dataset should not
 * change because somebody rearranged a table. It also needs fields the portal does not
 * show, like the location point.
 *
 * Unscoped by user, like AdminStore and unlike ChatStore. Everything here is behind an API
 * key that is granted the whole corpus on purpose.
 */
interface ApiStore {
    suspend fun users(limit: Int, offset: Int): List<ApiUser>

    suspend fun assessments(
        limit: Int,
        offset: Int,
        userId: String?,
        itemTypeId: String?,
        testersOnly: Boolean,
        /** Epoch millis. Only assessments updated at or after this, for incremental pulls. */
        updatedSince: Long?,
    ): List<ApiAssessment>

    suspend fun assessment(id: String): ApiAssessmentDetail?

    suspend fun testerFeedback(limit: Int, offset: Int): List<ApiTesterFeedback>

    /** Every roster entry of every manager, archived ones included, oldest first. */
    suspend fun workers(limit: Int, offset: Int): List<ApiWorker>

    /** Every link ever made between two accounts (V31), with its end where it has one, oldest first. */
    suspend fun workerLinks(limit: Int, offset: Int): List<ApiWorkerLink>

    /** Every piece (V14), oldest first, with its studio label (V34). */
    suspend fun pieces(limit: Int, offset: Int): List<ApiPiece>

    /** Every change of a piece's studio label after creation (V34), oldest first. */
    suspend fun pieceStudioChanges(limit: Int, offset: Int): List<ApiPieceStudioChange>

    /** Every answer to "Which studio are you at today?" (V34), by day, then account. */
    suspend fun studioDays(limit: Int, offset: Int): List<ApiStudioDay>

    /** Testers' answers to "Was this review helpful?" (V36), oldest first. */
    suspend fun reviewHelpful(limit: Int, offset: Int): List<ApiReviewHelpful>

    /** Every Fundi Bora detail answer (V37), prefetched or tapped, oldest first. */
    suspend fun detailAnswers(limit: Int, offset: Int): List<ApiDetailAnswer>

    /** Every bench photo (V35), oldest first, with its point and the draft Claude made. */
    suspend fun benchPhotos(limit: Int, offset: Int): List<ApiBenchPhoto>

    /** Every report issued (V32), oldest first, with the names it printed left out. */
    suspend fun reports(limit: Int, offset: Int): List<ApiReport>

    /** Every PesaLog transaction (V33), in the order the server received them. */
    suspend fun trackerTransactions(limit: Int, offset: Int): List<ApiTrackerTransaction>

    /** Every PesaLog unread count (V33), by account, network and day. */
    suspend fun trackerUnreadCounts(limit: Int, offset: Int): List<ApiTrackerUnreadCount>

    /** Every deletion a person asked for from PesaLog (V33), oldest first. */
    suspend fun trackerDeletions(limit: Int, offset: Int): List<ApiTrackerDeletion>

    /** Whether any assessment refers to this photo. Guards the bytes endpoint. */
    suspend fun photoExists(sha256: String): Boolean
}

class PostgresApiStore(private val dataSource: DataSource) : ApiStore {

    private suspend fun <T> query(block: (java.sql.Connection) -> T): T =
        withContext(Dispatchers.IO) { dataSource.connection.use(block) }

    override suspend fun users(limit: Int, offset: Int): List<ApiUser> = query { connection ->
        connection.prepareStatement(
            """
            select u.id::text, u.phone, u.display_name, u.account_type, u.business_name,
                   ST_Y(u.business_location::geometry), ST_X(u.business_location::geometry),
                   u.business_location_accuracy_m, u.is_tester,
                   (extract(epoch from u.created_at) * 1000)::bigint,
                   (select count(*)::int from sessions s where s.user_id = u.id),
                   (u.deleted_at is not null), u.audience, u.shop_mode
              from users u
             order by u.created_at
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiUser>()
                while (rows.next()) out += ApiUser(
                    id = rows.getString(1),
                    phone = rows.getString(2),
                    name = rows.getString(3),
                    accountType = rows.getString(4),
                    businessName = rows.getString(5),
                    // Y then X: ST_Y is the latitude. Reversed, every workshop in Nairobi
                    // lands in the Indian Ocean and nothing errors.
                    latitude = rows.nullableDouble(6),
                    longitude = rows.nullableDouble(7),
                    locationAccuracyM = rows.nullableDouble(8),
                    isTester = rows.getBoolean(9),
                    createdAt = rows.getLong(10),
                    assessments = rows.getInt(11),
                    deleted = rows.getBoolean(12),
                    audience = rows.getString(13),
                    shopMode = rows.getBoolean(14),
                )
                out
            }
        }
    }

    override suspend fun assessments(
        limit: Int,
        offset: Int,
        userId: String?,
        itemTypeId: String?,
        testersOnly: Boolean,
        updatedSince: Long?,
    ): List<ApiAssessment> = query { connection ->
        connection.prepareStatement(
            """
            $ASSESSMENT_COLUMNS
              from sessions s left join users u on u.id = s.user_id
             where (?::uuid is null or s.user_id = ?::uuid)
               and (?::text is null or s.item_type_id = ?)
               and (not ? or coalesce(u.is_tester, false))
               and (?::bigint is null
                    or s.updated_at >= to_timestamp(?::bigint / 1000.0))
             order by s.updated_at
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, userId)
            statement.setString(3, itemTypeId)
            statement.setString(4, itemTypeId)
            statement.setBoolean(5, testersOnly)
            setNullableLong(statement, 6, updatedSince)
            setNullableLong(statement, 7, updatedSince)
            statement.setInt(8, limit)
            statement.setInt(9, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiAssessment>()
                while (rows.next()) out += rows.toAssessment()
                out
            }
        }
    }

    override suspend fun assessment(id: String): ApiAssessmentDetail? = query { connection ->
        val assessment = connection.prepareStatement(
            """
            $ASSESSMENT_COLUMNS
              from sessions s left join users u on u.id = s.user_id
             where s.id = ?::uuid
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { if (it.next()) it.toAssessment() else null }
        } ?: return@query null

        val messages = connection.prepareStatement(
            """
            select m.role, m.text, (extract(epoch from m.created_at) * 1000)::bigint,
                   coalesce(
                       (select array_agg(a.sha256 order by a.ordinal, a.id)
                          from attachments a where a.message_id = m.id),
                       '{}'
                   ),
                   -- Same order as the hashes, so entry n describes photo n.
                   coalesce(
                       (select array_agg(a.photo_meta::text order by a.ordinal, a.id)
                          from attachments a where a.message_id = m.id),
                       '{}'
                   )
              from messages m
             where m.session_id = ?::uuid
             order by m.ordinal
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiMessage>()
                while (rows.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val photos = (rows.getArray(4).array as Array<String>).toList()
                    @Suppress("UNCHECKED_CAST")
                    val meta = (rows.getArray(5).array as Array<String?>).map { photoMetaOf(it) }
                    out += ApiMessage(
                        role = rows.getString(1),
                        text = rows.getString(2),
                        createdAt = rows.getLong(3),
                        photos = photos,
                        photoMeta = meta,
                    )
                }
                out
            }
        }

        val feedback = connection.prepareStatement(
            """
            select session_id::text, mistakes, mistakes_detail,
                   advice_stars, item_quality, extra_feedback
              from tester_feedback where session_id = ?::uuid
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { if (it.next()) it.toFeedback() else null }
        }

        // In insertion order, as the phone listed them. Only here, not on the list: the
        // roster route carries the names, and a page of assessments need not join to it.
        val workers = connection.prepareStatement(
            "select worker_id::text, part from session_workers where session_id = ?::uuid order by id"
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiSessionWorker>()
                while (rows.next()) out += ApiSessionWorker(rows.getString(1), rows.getString(2))
                out
            }
        }

        // The tools to hand when it started (V35), as the server worked them out. Only here,
        // for the reason the workers are.
        val tools = connection.prepareStatement(
            "select kind, source, owner from session_tools where session_id = ?::uuid order by source, kind"
        ).use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiSessionTool>()
                while (rows.next()) out += ApiSessionTool(rows.getString(1), rows.getString(2), rows.getString(3))
                out
            }
        }

        ApiAssessmentDetail(assessment, messages, feedback, workers = workers, tools = tools)
    }

    override suspend fun benchPhotos(limit: Int, offset: Int): List<ApiBenchPhoto> = query { connection ->
        connection.prepareStatement(
            """
            select b.id::text, b.user_id::text, b.sha256,
                   ST_Y(b.location::geometry), ST_X(b.location::geometry), b.location_accuracy_m,
                   (extract(epoch from b.taken_at) * 1000)::bigint,
                   b.draft::text, b.prompt_sha, b.model
              from bench_photos b
             order by b.taken_at, b.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiBenchPhoto>()
                while (rows.next()) out += ApiBenchPhoto(
                    id = rows.getString(1),
                    userId = rows.getString(2),
                    sha256 = rows.getString(3).trim(),
                    // Y then X: ST_Y is the latitude, as for users.
                    latitude = rows.nullableDouble(4),
                    longitude = rows.nullableDouble(5),
                    locationAccuracyM = rows.nullableDouble(6),
                    takenAt = rows.getLong(7),
                    draft = photoMetaOf(rows.getString(8)),
                    promptSha = rows.getString(9),
                    model = rows.getString(10),
                )
                out
            }
        }
    }

    override suspend fun workers(limit: Int, offset: Int): List<ApiWorker> = query { connection ->
        connection.prepareStatement(
            """
            select w.id::text, w.user_id::text, w.name, w.phone, w.employment,
                   (extract(epoch from w.archived_at) * 1000)::bigint,
                   (extract(epoch from w.created_at) * 1000)::bigint,
                   (extract(epoch from w.updated_at) * 1000)::bigint
              from workers w
             order by w.created_at, w.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiWorker>()
                while (rows.next()) out += ApiWorker(
                    id = rows.getString(1),
                    userId = rows.getString(2),
                    name = rows.getString(3),
                    phone = rows.getString(4),
                    employment = rows.getString(5),
                    archivedAt = rows.nullableLong(6),
                    createdAt = rows.getLong(7),
                    updatedAt = rows.getLong(8),
                )
                out
            }
        }
    }

    override suspend fun testerFeedback(limit: Int, offset: Int): List<ApiTesterFeedback> =
        query { connection ->
            connection.prepareStatement(
                """
                select session_id::text, mistakes, mistakes_detail,
                       advice_stars, item_quality, extra_feedback
                  from tester_feedback
                 order by created_at
                 limit ? offset ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { rows ->
                    val out = mutableListOf<ApiTesterFeedback>()
                    while (rows.next()) out += rows.toFeedback()
                    out
                }
            }
        }

    override suspend fun workerLinks(limit: Int, offset: Int): List<ApiWorkerLink> = query { connection ->
        connection.prepareStatement(
            """
            select l.worker_user_id::text, l.studio_user_id::text, l.worker_entry_id::text, l.employment,
                   (extract(epoch from l.at) * 1000)::bigint,
                   -- The first 'ended' after this 'linked' for the same pair, or null.
                   (select (extract(epoch from e.at) * 1000)::bigint
                      from worker_link_events e
                     where e.worker_user_id = l.worker_user_id
                       and e.studio_user_id = l.studio_user_id
                       and e.event = 'ended' and e.id > l.id
                     order by e.id limit 1)
              from worker_link_events l
             where l.event = 'linked'
             order by l.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiWorkerLink>()
                while (rows.next()) out += ApiWorkerLink(
                    workerUserId = rows.getString(1),
                    studioUserId = rows.getString(2),
                    workerEntryId = rows.getString(3),
                    employment = rows.getString(4),
                    linkedSince = rows.getLong(5),
                    endedAt = rows.nullableLong(6),
                )
                out
            }
        }
    }

    override suspend fun pieces(limit: Int, offset: Int): List<ApiPiece> = query { connection ->
        connection.prepareStatement(
            """
            select p.id::text, p.user_id::text, p.item_type_id,
                   (extract(epoch from p.created_at) * 1000)::bigint,
                   p.studio_entry_id::text,
                   (extract(epoch from p.studio_chosen_at) * 1000)::bigint
              from pieces p
             order by p.created_at, p.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiPiece>()
                while (rows.next()) out += ApiPiece(
                    id = rows.getString(1),
                    userId = rows.getString(2),
                    itemTypeId = rows.getString(3),
                    createdAt = rows.getLong(4),
                    studioEntryId = rows.getString(5),
                    studioChosenAt = rows.nullableLong(6),
                )
                out
            }
        }
    }

    override suspend fun pieceStudioChanges(limit: Int, offset: Int): List<ApiPieceStudioChange> =
        query { connection ->
            connection.prepareStatement(
                """
                select c.id, c.piece_id::text, c.user_id::text, c.kind,
                       c.old_studio_entry_id::text, c.new_studio_entry_id::text,
                       (extract(epoch from c.at) * 1000)::bigint
                  from piece_studio_changes c
                 order by c.id
                 limit ? offset ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { rows ->
                    val out = mutableListOf<ApiPieceStudioChange>()
                    while (rows.next()) out += ApiPieceStudioChange(
                        id = rows.getLong(1),
                        pieceId = rows.getString(2),
                        userId = rows.getString(3),
                        kind = rows.getString(4),
                        oldStudioEntryId = rows.getString(5),
                        newStudioEntryId = rows.getString(6),
                        at = rows.getLong(7),
                    )
                    out
                }
            }
        }

    override suspend fun studioDays(limit: Int, offset: Int): List<ApiStudioDay> = query { connection ->
        connection.prepareStatement(
            """
            select d.user_id::text, d.day::text, d.studio_entry_id::text,
                   (extract(epoch from d.answered_at) * 1000)::bigint
              from fundi_studio_days d
             order by d.day, d.user_id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiStudioDay>()
                while (rows.next()) out += ApiStudioDay(
                    userId = rows.getString(1),
                    day = rows.getString(2),
                    studioEntryId = rows.getString(3),
                    answeredAt = rows.getLong(4),
                )
                out
            }
        }
    }

    override suspend fun reviewHelpful(limit: Int, offset: Int): List<ApiReviewHelpful> = query { connection ->
        connection.prepareStatement(
            """
            select h.session_id::text, h.user_id::text, h.helpful,
                   (extract(epoch from h.answered_at) * 1000)::bigint
              from fundi_review_helpful h
             order by h.answered_at, h.session_id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiReviewHelpful>()
                while (rows.next()) out += ApiReviewHelpful(
                    sessionId = rows.getString(1),
                    userId = rows.getString(2),
                    helpful = rows.getBoolean(3),
                    answeredAt = rows.getLong(4),
                )
                out
            }
        }
    }

    override suspend fun detailAnswers(limit: Int, offset: Int): List<ApiDetailAnswer> = query { connection ->
        connection.prepareStatement(
            """
            select d.id::text, d.session_id::text, d.source_message_id::text, d.finding_index,
                   d.finding_title, d.detail_kind, d.source, d.answer_text, d.model, d.effort,
                   d.prompt_sha, d.usage_event_id,
                   (extract(epoch from d.created_at) * 1000)::bigint,
                   (extract(epoch from d.opened_at) * 1000)::bigint,
                   d.opened_message_id::text
              from fundi_detail_answers d
             order by d.created_at, d.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiDetailAnswer>()
                while (rows.next()) out += ApiDetailAnswer(
                    id = rows.getString(1),
                    sessionId = rows.getString(2),
                    sourceMessageId = rows.getString(3),
                    findingIndex = rows.getInt(4).takeUnless { rows.wasNull() },
                    findingTitle = rows.getString(5),
                    detailKind = rows.getString(6),
                    source = rows.getString(7),
                    answerText = rows.getString(8),
                    model = rows.getString(9),
                    effort = rows.getString(10),
                    promptSha = rows.getString(11),
                    usageEventId = rows.getLong(12).takeUnless { rows.wasNull() },
                    createdAt = rows.getLong(13),
                    openedAt = rows.getLong(14).takeUnless { rows.wasNull() },
                    openedMessageId = rows.getString(15),
                )
                out
            }
        }
    }

    override suspend fun reports(limit: Int, offset: Int): List<ApiReport> = query { connection ->
        connection.prepareStatement(
            """
            select r.id::text, r.piece_id::text, r.user_id::text, r.kind,
                   r.session_ids::text[], r.parts, r.worker_id::text, r.defect_refs,
                   r.defect_snapshot::text, r.sha256, r.byte_size,
                   (extract(epoch from r.issued_at) * 1000)::bigint
              from reports r
             order by r.issued_at, r.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiReport>()
                while (rows.next()) {
                    @Suppress("UNCHECKED_CAST")
                    val sessionIds = (rows.getArray(5).array as Array<String>).toList()
                    @Suppress("UNCHECKED_CAST")
                    val parts = rows.getArray(6)?.let { (it.array as Array<String>).toList() }
                    @Suppress("UNCHECKED_CAST")
                    val refs = (rows.getArray(8).array as Array<String>).toList()
                    out += ApiReport(
                        id = rows.getString(1),
                        pieceId = rows.getString(2),
                        userId = rows.getString(3),
                        kind = rows.getString(4),
                        sessionIds = sessionIds,
                        parts = parts,
                        workerId = rows.getString(7),
                        defectRefs = refs,
                        defectSnapshot = snapshotWithoutNames(rows.getString(9)),
                        sha256 = rows.getString(10),
                        byteSize = rows.getInt(11),
                        issuedAt = rows.getLong(12),
                    )
                }
                out
            }
        }
    }

    // By id, which is the order of first receipt and never changes, so offset paging over a
    // table that only grows at the end does not skip or repeat rows. A row deleted on request
    // between two pages shifts the later ones by one; a pull is run end to end, not resumed.
    override suspend fun trackerTransactions(limit: Int, offset: Int): List<ApiTrackerTransaction> =
        query { connection ->
            connection.prepareStatement(
                """
                select t.id, t.user_id::text, t.network, t.code, t.direction, t.tx_type,
                       t.amount_kes::float8, t.fee_kes::float8, t.balance_after_kes::float8,
                       (extract(epoch from t.occurred_at) * 1000)::bigint,
                       t.counterparty_code, t.counterparty_basis, t.parser_version, t.app_version,
                       (extract(epoch from t.received_at) * 1000)::bigint,
                       t.category, t.comment,
                       (extract(epoch from t.annotated_at) * 1000)::bigint
                  from tracker_transactions t
                 order by t.id
                 limit ? offset ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { rows ->
                    val out = mutableListOf<ApiTrackerTransaction>()
                    while (rows.next()) out += ApiTrackerTransaction(
                        id = rows.getLong(1),
                        userId = rows.getString(2),
                        network = rows.getString(3),
                        code = rows.getString(4),
                        direction = rows.getString(5),
                        type = rows.getString(6),
                        amountKes = rows.getDouble(7),
                        feeKes = rows.nullableDouble(8),
                        balanceAfterKes = rows.nullableDouble(9),
                        occurredAt = rows.getLong(10),
                        counterpartyCode = rows.getString(11),
                        counterpartyBasis = rows.getString(12),
                        parserVersion = rows.getInt(13),
                        appVersion = rows.getInt(14),
                        receivedAt = rows.getLong(15),
                        category = rows.getString(16),
                        comment = rows.getString(17),
                        annotatedAt = rows.nullableLong(18),
                    )
                    out
                }
            }
        }

    override suspend fun trackerUnreadCounts(limit: Int, offset: Int): List<ApiTrackerUnreadCount> =
        query { connection ->
            connection.prepareStatement(
                """
                select u.user_id::text, u.network, u.day::text, u.unread, u.parser_version, u.app_version,
                       (extract(epoch from u.updated_at) * 1000)::bigint
                  from tracker_unread_counts u
                 order by u.user_id, u.network, u.day
                 limit ? offset ?
                """.trimIndent()
            ).use { statement ->
                statement.setInt(1, limit)
                statement.setInt(2, offset)
                statement.executeQuery().use { rows ->
                    val out = mutableListOf<ApiTrackerUnreadCount>()
                    while (rows.next()) out += ApiTrackerUnreadCount(
                        userId = rows.getString(1),
                        network = rows.getString(2),
                        day = rows.getString(3),
                        unread = rows.getInt(4),
                        parserVersion = rows.getInt(5),
                        appVersion = rows.getInt(6),
                        updatedAt = rows.getLong(7),
                    )
                    out
                }
            }
        }

    override suspend fun trackerDeletions(limit: Int, offset: Int): List<ApiTrackerDeletion> = query { connection ->
        connection.prepareStatement(
            """
            select d.id, d.user_id::text, (extract(epoch from d.deleted_at) * 1000)::bigint,
                   d.transactions_deleted, d.unread_days_deleted, d.app_version
              from tracker_deletions d
             order by d.id
             limit ? offset ?
            """.trimIndent()
        ).use { statement ->
            statement.setInt(1, limit)
            statement.setInt(2, offset)
            statement.executeQuery().use { rows ->
                val out = mutableListOf<ApiTrackerDeletion>()
                while (rows.next()) out += ApiTrackerDeletion(
                    id = rows.getLong(1),
                    userId = rows.getString(2),
                    deletedAt = rows.getLong(3),
                    transactionsDeleted = rows.getInt(4),
                    unreadDaysDeleted = rows.getInt(5),
                    appVersion = rows.nullableInt(6),
                )
                out
            }
        }
    }

    override suspend fun photoExists(sha256: String): Boolean = query { connection ->
        // A bench photo (V35) too, so its bytes can be checked against the draft it made.
        connection.prepareStatement(
            """
            select exists (select 1 from attachments where sha256 = ?)
                or exists (select 1 from bench_photos where sha256 = ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, sha256)
            statement.setString(2, sha256)
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    }

    private fun ResultSet.toAssessment(): ApiAssessment {
        val intake = ApiIntake.of(getString(14))
        return ApiAssessment(
            id = getString(1),
            userId = getString(2),
            itemTypeId = getString(3),
            createdAt = getLong(4),
            updatedAt = getLong(5),
            messageCount = getInt(6),
            photoCount = getInt(7),
            verdictLevelId = getString(8),
            byTester = getBoolean(9),
            hasTesterFeedback = getBoolean(10),
            deletedByUser = getBoolean(11),
            audience = getString(12),
            appVersionCode = nullableInt(13),
            purpose = intake.purpose,
            language = intake.language,
            pieceId = getString(15),
            promptSha = getString(16),
            assessedAs = getString(17),
            final = getBoolean(18),
            shopTablet = getBoolean(19),
            tabletEnded = getString(20),
            tabletEndedAt = nullableLong(21),
        )
    }

    private fun ResultSet.toFeedback() = ApiTesterFeedback(
        sessionId = getString(1),
        mistakes = getString(2),
        mistakesDetail = getString(3),
        adviceStars = getInt(4),
        itemQuality = getInt(5),
        extraFeedback = getString(6),
    )

    /**
     * One stored `photo_meta`, or null. A value that does not read as an object (V25's CHECK
     * says it cannot happen) is reported as null rather than failing the whole assessment.
     */
    private fun photoMetaOf(text: String?): JsonObject? = text?.let {
        runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull()
    }

    /** getDouble returns 0.0 for SQL null, which would put a workshop off the Gulf of Guinea. */
    private fun ResultSet.nullableDouble(index: Int): Double? {
        val value = getDouble(index)
        return if (wasNull()) null else value
    }

    /** getInt returns 0 for SQL null, which would read as a version that never existed. */
    private fun ResultSet.nullableInt(index: Int): Int? {
        val value = getInt(index)
        return if (wasNull()) null else value
    }

    /** getLong returns 0 for SQL null, which would read as archived on 1 January 1970. */
    private fun ResultSet.nullableLong(index: Int): Long? {
        val value = getLong(index)
        return if (wasNull()) null else value
    }

    private fun setNullableLong(statement: java.sql.PreparedStatement, index: Int, value: Long?) {
        if (value == null) statement.setNull(index, java.sql.Types.BIGINT)
        else statement.setLong(index, value)
    }

    private companion object {
        /** Shared so the list and the detail cannot drift into reporting different things. */
        const val ASSESSMENT_COLUMNS = """
            select s.id::text, s.user_id::text, s.item_type_id,
                   (extract(epoch from s.created_at) * 1000)::bigint,
                   (extract(epoch from s.updated_at) * 1000)::bigint,
                   (select count(*)::int from messages m where m.session_id = s.id),
                   (select count(*)::int from attachments a
                      join messages m2 on m2.id = a.message_id
                     where m2.session_id = s.id),
                   s.verdict_level_id,
                   coalesce(u.is_tester, false),
                   exists (select 1 from tester_feedback f where f.session_id = s.id),
                   (s.client_deleted_at is not null),
                   s.audience,
                   s.app_version_code,
                   s.intake_answers,
                   s.piece_id::text,
                   s.prompt_sha,
                   s.assessed_as,
                   s.is_final,
                   s.shop_tablet,
                   s.tablet_ended,
                   (extract(epoch from s.tablet_ended_at) * 1000)::bigint
        """
    }
}

/**
 * A report's snapshot with every name taken out: `made_by`, and `name` on each worker row.
 * The data API carries worker ids and never worker names (plan v7 §PR 2); the row keeps
 * them. A snapshot that does not read as an object, which V32 does not allow, is reported
 * empty rather than failing the page.
 */
internal fun snapshotWithoutNames(text: String?): JsonObject {
    val snapshot = text?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
        ?: return JsonObject(emptyMap())
    val workers = snapshot["workers"]?.let { element ->
        runCatching {
            JsonArray(element.jsonArray.map { worker -> JsonObject(worker.jsonObject.filterKeys { it != "name" }) })
        }.getOrNull()
    }
    return JsonObject(
        snapshot.filterKeys { it != "made_by" && it != "workers" } +
            (if (workers != null) mapOf("workers" to workers) else emptyMap()),
    )
}

/**
 * The purpose and language of an assessment, out of `sessions.intake_answers`.
 *
 * Decoded here rather than published raw: the column holds two unrelated codes, Kagua's
 * four-part one and Fundi Bora's `fundi-<language>-<purpose>` ([FundiIntake.encode]), and a
 * consumer splitting strings would have to know both. Both null or both set, as
 * [FundiIntake.decode] gives them: Kagua's code, an empty column, and a Fundi Bora code
 * naming a language or purpose this server does not know all read as "not known".
 */
internal data class ApiIntake(val purpose: String?, val language: String?) {
    companion object {
        fun of(intakeAnswers: String?): ApiIntake {
            val decoded = FundiIntake.decode(intakeAnswers) ?: return ApiIntake(null, null)
            val (language, purpose) = decoded
            return ApiIntake(purpose = purpose.id, language = language.code)
        }
    }
}
