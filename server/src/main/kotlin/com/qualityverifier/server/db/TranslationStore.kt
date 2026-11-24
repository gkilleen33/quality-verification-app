package com.qualityverifier.server.db

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.sql.DataSource

/** One translation to keep (V41): one reply of one assessment, in one language. */
data class NewTranslation(
    val sessionId: String,
    /** The first stored reply with this text; the key is the text's hash, not this. */
    val messageId: String?,
    /** SHA-256 of the original reply's text (ReplyTranslation.keyOf). */
    val sourceSha256: String,
    /** "en" or "sw". */
    val language: String,
    val text: String,
    val model: String?,
    /** SHA-256 of the translation prompt that wrote it, lowercase hex: part of the row's key. */
    val promptSha: String,
    val usageEventId: Long?,
)

/**
 * Translations of Fundi Bora replies (V41, release scope row 29).
 *
 * Kept apart from `messages` on purpose, as the detail answers are (V37): nothing that builds
 * a request to Claude reads this table, so a translation can never reach the conversation or
 * change the bytes of its cached prefix. A reply is named by the hash of its text, because
 * the phone's id for a reply it received is its own, not the server's.
 */
interface TranslationStore {
    /**
     * Every stored translation of this assessment's replies into [language] written by the
     * prompt whose SHA-256 is [promptSha], by source hash. One written by an earlier prompt is
     * not served: a new prompt (row 16's glossary) replaces it on the next switch.
     */
    suspend fun find(sessionId: String, language: String, promptSha: String): Map<String, String>

    /** Keeps [translation]. A second one for the same reply, language and prompt is ignored: the first stands. */
    suspend fun save(translation: NewTranslation)
}

class PostgresTranslationStore(private val dataSource: DataSource) : TranslationStore {

    override suspend fun find(sessionId: String, language: String, promptSha: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            dataSource.connection.use { connection ->
                connection.prepareStatement(
                    """
                    select source_sha256, translated_text from message_translations
                     where session_id = ?::uuid and language = ? and prompt_sha = ?
                       and translated_text is not null
                    """.trimIndent()
                ).use { statement ->
                    statement.setString(1, sessionId)
                    statement.setString(2, language)
                    statement.setString(3, promptSha)
                    statement.executeQuery().use { rows ->
                        val out = mutableMapOf<String, String>()
                        while (rows.next()) out[rows.getString(1).trim()] = rows.getString(2)
                        out
                    }
                }
            }
        }

    override suspend fun save(translation: NewTranslation) = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """
                insert into message_translations (
                    session_id, message_id, source_sha256, language, translated_text,
                    model, prompt_sha, usage_event_id
                ) values (?::uuid, ?::uuid, ?, ?, ?, ?, ?, ?)
                on conflict (session_id, language, source_sha256, prompt_sha) do nothing
                """.trimIndent()
            ).use { statement ->
                statement.setString(1, translation.sessionId)
                statement.setString(2, translation.messageId)
                statement.setString(3, translation.sourceSha256)
                statement.setString(4, translation.language)
                statement.setString(5, translation.text)
                statement.setString(6, translation.model)
                statement.setString(7, translation.promptSha)
                if (translation.usageEventId == null) statement.setNull(8, java.sql.Types.BIGINT)
                else statement.setLong(8, translation.usageEventId)
                statement.executeUpdate()
            }
            Unit
        }
    }
}
