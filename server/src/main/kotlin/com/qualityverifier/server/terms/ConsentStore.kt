package com.qualityverifier.server.terms

import com.qualityverifier.domain.Audience
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import javax.sql.DataSource

/**
 * What an account can agree to. Mirrored by `consents_kind_known`, which V29 replaced to add
 * the two M-Pesa questions and V33 replaced to add PesaLog's four.
 */
enum class ConsentKind(val id: String) {
    /** The first box on the terms screen. */
    TERMS("terms"),

    /** The second box: the transfer to the United States, section 14. */
    TRANSFER("transfer"),

    /** May Fundi Bora read M-Pesa messages and keep them on the phone (MpesaConsentText.READ). */
    MPESA_READ("mpesa_read"),

    /** May it send them to the server. Asked only after [MPESA_READ] (MpesaConsentText.SEND). */
    MPESA_SEND("mpesa_send"),

    /**
     * The first box on PesaLog's terms screen. Its own kind rather than [TERMS]: a PesaLog
     * account is a Fundi Bora account, and the newest `terms` row is what Fundi Bora's gate
     * reads, so an agreement to PesaLog's text under that kind would stand in for, or
     * overwrite, the person's agreement to Fundi Bora's.
     */
    TRACKER_TERMS("tracker_terms"),

    /** The second box on PesaLog's terms screen: the transfer to the United States, section 10. */
    TRACKER_TRANSFER("tracker_transfer"),

    /** May PesaLog read mobile money notifications and keep them on the phone (TrackerConsentText.READ). */
    TRACKER_READ("tracker_read"),

    /** May it send them to the research team. Asked only after [TRACKER_READ] (TrackerConsentText.SEND). */
    TRACKER_SEND("tracker_send");

    companion object {
        fun fromId(id: String?): ConsentKind? = entries.firstOrNull { it.id == id }

        /** The two boxes on the terms screen, agreed together, and what [TermsGate] checks. */
        val TERMS_SCREEN: List<ConsentKind> = listOf(TERMS, TRANSFER)

        /** The two boxes on PesaLog's terms screen, agreed together, and what the tracker routes check. */
        val TRACKER_TERMS_SCREEN: List<ConsentKind> = listOf(TRACKER_TERMS, TRACKER_TRANSFER)

        /** The questions each app asks one at a time, each against its own published text. */
        val QUESTIONS: List<ConsentKind> = listOf(MPESA_READ, MPESA_SEND, TRACKER_READ, TRACKER_SEND)

        /** The kinds that can be withdrawn. Withdrawing either app's terms is deleting the account. */
        val WITHDRAWABLE: List<ConsentKind> =
            listOf(TRANSFER, MPESA_READ, MPESA_SEND, TRACKER_TRANSFER, TRACKER_READ, TRACKER_SEND)
    }
}

/** Mirrored by V28's `consents_action_known`. Only [ConsentKind.WITHDRAWABLE] kinds can be withdrawn. */
enum class ConsentAction(val id: String) {
    AGREED("agreed"),
    WITHDRAWN("withdrawn");

    companion object {
        fun fromId(id: String?): ConsentAction? = entries.firstOrNull { it.id == id }
    }
}

/** An account's newest row for one kind. [version] is null on a withdrawal. */
data class ConsentRecord(val action: ConsentAction, val version: String?)

/** What an agreement records besides the account. The server fills [audience]; the phone never sends it. */
data class Agreement(
    val audience: Audience,
    val version: String,
    val language: TermsLanguage,
    val textSha256: String,
    val appVersion: Int,
)

/**
 * The consent records (V28): append-only, one row per agreement or withdrawal.
 *
 * An interface for the same reason as the other stores: the rules worth testing — which
 * rows let an account through — live in [TermsGate], and stating them needs a fake.
 */
interface ConsentStore {
    /** The newest row for each kind the account has any row for. */
    suspend fun current(userId: String): Map<ConsentKind, ConsentRecord>

    /**
     * Records agreement to [kinds], in one transaction. Each terms screen sends both of its
     * boxes at once, so an account never holds one without the other; each M-Pesa or PesaLog
     * question is its own agreement to its own text.
     */
    suspend fun agree(userId: String, agreement: Agreement, kinds: List<ConsentKind>)

    /**
     * Records a withdrawal of [kind] when the account's newest row for it is an agreement.
     * Returns false, writing nothing, when there was nothing to withdraw.
     */
    suspend fun withdraw(userId: String, audience: Audience, kind: ConsentKind, appVersion: Int): Boolean
}

class PostgresConsentStore(private val dataSource: DataSource) : ConsentStore {

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

    // One index range scan on consents_current_idx: the newest row per kind.
    override suspend fun current(userId: String): Map<ConsentKind, ConsentRecord> = tx { connection ->
        connection.prepareStatement(
            """
            select distinct on (kind) kind, action, version
              from consents
             where user_id = ?::uuid
             order by kind, id desc
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.executeQuery().use { rows ->
                val out = mutableMapOf<ConsentKind, ConsentRecord>()
                while (rows.next()) {
                    val kind = ConsentKind.fromId(rows.getString(1)) ?: continue
                    val action = ConsentAction.fromId(rows.getString(2)) ?: continue
                    out[kind] = ConsentRecord(action, rows.getString(3))
                }
                out
            }
        }
    }

    override suspend fun agree(userId: String, agreement: Agreement, kinds: List<ConsentKind>): Unit = tx { connection ->
        connection.prepareStatement(
            """
            insert into consents (
                user_id, kind, action, version, language, text_sha256, audience, app_version
            ) values (?::uuid, ?, 'agreed', ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            kinds.forEach { kind ->
                statement.setString(1, userId)
                statement.setString(2, kind.id)
                statement.setString(3, agreement.version)
                statement.setString(4, agreement.language.id)
                statement.setString(5, agreement.textSha256)
                statement.setString(6, agreement.audience.id)
                statement.setInt(7, agreement.appVersion)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    // One statement, so the check and the insert see the same newest row.
    override suspend fun withdraw(
        userId: String,
        audience: Audience,
        kind: ConsentKind,
        appVersion: Int,
    ): Boolean = tx { connection ->
        connection.prepareStatement(
            """
            insert into consents (user_id, kind, action, audience, app_version)
            select ?::uuid, ?, 'withdrawn', ?, ?
             where (select action from consents
                     where user_id = ?::uuid and kind = ?
                     order by id desc limit 1) = 'agreed'
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, userId)
            statement.setString(2, kind.id)
            statement.setString(3, audience.id)
            statement.setInt(4, appVersion)
            statement.setString(5, userId)
            statement.setString(6, kind.id)
            statement.executeUpdate() > 0
        }
    }
}
