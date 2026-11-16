package com.qualityverifier.tracker.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** PesaLog's tables (database version 1). An interface so the repository can be tested with a fake. */
@Dao
interface TrackerDao {

    /** -1 when the network and code are already stored: a second read of one message changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(transaction: TransactionEntity): Long

    /** Fills a stored row's missing fee or balance from a fuller copy of its message, before upload only. */
    @Query(
        "UPDATE transactions SET feeKes = COALESCE(feeKes, :feeKes), " +
            "balanceAfterKes = COALESCE(balanceAfterKes, :balanceAfterKes) " +
            "WHERE network = :network AND code = :code AND uploadedAt IS NULL"
    )
    suspend fun fillMissing(network: String, code: String, feeKes: Double?, balanceAfterKes: Double?)

    @Query("SELECT * FROM transactions ORDER BY occurredAt DESC, network, code")
    fun observeAll(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE network = :network AND code = :code")
    fun observe(network: String, code: String): Flow<TransactionEntity?>

    @Query(
        "UPDATE transactions SET category = :category, comment = :comment, annotatedAt = :at " +
            "WHERE network = :network AND code = :code"
    )
    suspend fun annotate(network: String, code: String, category: String?, comment: String?, at: Long)

    @Query("SELECT * FROM transactions WHERE uploadedAt IS NULL ORDER BY occurredAt LIMIT :limit")
    suspend fun notUploaded(limit: Int): List<TransactionEntity>

    /**
     * Marked once and never unmarked: after the person deletes the server's copy, the rows it
     * held stay marked, so turning sending on again later does not send them back.
     */
    @Query("UPDATE transactions SET uploadedAt = :at WHERE network = :network AND code = :code")
    suspend fun markUploaded(network: String, code: String, at: Long)

    @Query(
        "SELECT * FROM transactions WHERE uploadedAt IS NOT NULL AND annotatedAt IS NOT NULL " +
            "AND (annotationSyncedAt IS NULL OR annotationSyncedAt < annotatedAt) ORDER BY annotatedAt"
    )
    suspend fun unsyncedAnnotations(): List<TransactionEntity>

    @Query(
        "UPDATE transactions SET annotationSyncedAt = :annotatedAt " +
            "WHERE network = :network AND code = :code AND annotatedAt = :annotatedAt"
    )
    suspend fun markAnnotationSynced(network: String, code: String, annotatedAt: Long)

    @Query("SELECT * FROM unread_days WHERE network = :network AND day = :day")
    suspend fun unreadDay(network: String, day: String): UnreadDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUnreadDay(day: UnreadDayEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCounted(counted: CountedUnreadEntity): Long

    @Query("DELETE FROM counted_unread WHERE countedAt < :before")
    suspend fun pruneCounted(before: Long)

    /**
     * Adds one to [network]'s count for [day], unless [counted] was already counted. Returns
     * whether it was new.
     */
    @Transaction
    suspend fun countUnread(network: String, day: String, counted: CountedUnreadEntity, at: Long): Boolean {
        if (insertCounted(counted) == -1L) return false
        val current = unreadDay(network, day)
        putUnreadDay(
            UnreadDayEntity(
                network = network,
                day = day,
                unread = (current?.unread ?: 0) + 1,
                updatedAt = at,
                syncedAt = current?.syncedAt,
            )
        )
        return true
    }

    @Query("SELECT * FROM unread_days WHERE syncedAt IS NULL OR syncedAt < updatedAt ORDER BY day, network")
    suspend fun unsyncedUnreadDays(): List<UnreadDayEntity>

    @Query(
        "UPDATE unread_days SET syncedAt = :updatedAt " +
            "WHERE network = :network AND day = :day AND updatedAt = :updatedAt"
    )
    suspend fun markUnreadDaySynced(network: String, day: String, updatedAt: Long)
}
