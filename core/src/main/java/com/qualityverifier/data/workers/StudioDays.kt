package com.qualityverifier.data.workers

import com.qualityverifier.data.db.SessionDao
import com.qualityverifier.data.db.StudioDayEntity
import com.qualityverifier.data.sync.SyncClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * "Which studio are you at today?" (server V34, release scope row 9; Nathan, 16 November
 * 2026): asked before a fundi's first assessment each Nairobi day, of an account with at
 * least one studio on its list. The answer is stored here, sent at once, and kept for the
 * next sync if the send fails. It changes no piece's label.
 *
 * The server keeps one answer per account and day, the first it receives, and dates it by
 * its own clock. So only today's answer is ever sent: one left over from an earlier day
 * would be filed under today, and is dropped instead.
 */
class StudioDays(
    private val dao: SessionDao,
    /** Null in tests that send nothing. */
    private val client: SyncClient?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Today's Nairobi date, as the server counts days. SimpleDateFormat rather than
     * java.time, which needs API 26; the apps run from 24.
     */
    fun today(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = NAIROBI }.format(Date(now()))

    /**
     * Whether today is answered on this phone. Read from the phone only, never waiting on
     * the server: a slow connection must not hold the assessment back. A fundi who answered
     * on another phone is asked again here; the server keeps the day's first answer.
     */
    suspend fun answeredToday(): Boolean = dao.studioDay(today()) != null

    /** Records today's answer and tries to send it. [studioId] null is the fundi's own workshop. */
    suspend fun answer(studioId: String?) {
        val day = today()
        dao.upsertStudioDay(StudioDayEntity(day, studioId, answeredAt = now()))
        push()
    }

    /** Sends today's answer if it is waiting; drops one from an earlier day. Returns how many went. */
    suspend fun push(): Int {
        val client = client ?: return 0
        val day = today()
        var sent = 0
        for (pending in dao.unsyncedStudioDays()) {
            if (pending.day != day) {
                dao.markStudioDaySynced(pending.day, now())
                continue
            }
            if (client.answerStudioToday(pending.studioId)) {
                dao.markStudioDaySynced(pending.day, now())
                sent++
            }
        }
        return sent
    }

    private companion object {
        val NAIROBI: TimeZone = TimeZone.getTimeZone("Africa/Nairobi")
    }
}
