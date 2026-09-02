package com.qualityverifier.server.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
                   (u.deleted_at is not null)
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
                    // Y then X: ST_Y is the latitude. Reversed, every workshop in Kampala
                    // lands in the Indian Ocean and nothing errors.
                    latitude = rows.nullableDouble(6),
                    longitude = rows.nullableDouble(7),
                    locationAccuracyM = rows.nullableDouble(8),
                    isTester = rows.getBoolean(9),
                    createdAt = rows.getLong(10),
                    assessments = rows.getInt(11),
                    deleted = rows.getBoolean(12),
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
                    out += ApiMessage(
                        role = rows.getString(1),
                        text = rows.getString(2),
                        createdAt = rows.getLong(3),
                        photos = photos,
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

        ApiAssessmentDetail(assessment, messages, feedback)
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

    override suspend fun photoExists(sha256: String): Boolean = query { connection ->
        connection.prepareStatement(
            "select exists (select 1 from attachments where sha256 = ?)"
        ).use { statement ->
            statement.setString(1, sha256)
            statement.executeQuery().use { it.next() && it.getBoolean(1) }
        }
    }

    private fun ResultSet.toAssessment() = ApiAssessment(
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
    )

    private fun ResultSet.toFeedback() = ApiTesterFeedback(
        sessionId = getString(1),
        mistakes = getString(2),
        mistakesDetail = getString(3),
        adviceStars = getInt(4),
        itemQuality = getInt(5),
        extraFeedback = getString(6),
    )

    /** getDouble returns 0.0 for SQL null, which would put a workshop off the Gulf of Guinea. */
    private fun ResultSet.nullableDouble(index: Int): Double? {
        val value = getDouble(index)
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
                   (s.client_deleted_at is not null)
        """
    }
}
