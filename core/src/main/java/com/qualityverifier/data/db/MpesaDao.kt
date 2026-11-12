package com.qualityverifier.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** Fundi Bora's M-Pesa tables (database version 13). Kagua never writes to them. */
@Dao
interface MpesaDao {

    /** -1 when the code is already stored: a second read of the same message changes nothing. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfNew(transaction: MpesaTransactionEntity): Long

    @Query("SELECT * FROM mpesa_transactions ORDER BY occurredAt DESC, code")
    fun observeAll(): Flow<List<MpesaTransactionEntity>>

    @Query("SELECT * FROM mpesa_transactions WHERE code = :code")
    fun observe(code: String): Flow<MpesaTransactionEntity?>

    @Query(
        "UPDATE mpesa_transactions SET category = :category, pieceId = :pieceId, comment = :comment, " +
            "annotatedAt = :at WHERE code = :code"
    )
    suspend fun annotate(code: String, category: String?, pieceId: String?, comment: String?, at: Long)

    @Query("SELECT * FROM mpesa_transactions WHERE uploadedAt IS NULL ORDER BY occurredAt LIMIT :limit")
    suspend fun notUploaded(limit: Int): List<MpesaTransactionEntity>

    @Query("UPDATE mpesa_transactions SET uploadedAt = :at WHERE code IN (:codes)")
    suspend fun markUploaded(codes: List<String>, at: Long)

    /** Notes the server has not confirmed, on transactions it already holds. */
    @Query(
        "SELECT * FROM mpesa_transactions WHERE uploadedAt IS NOT NULL AND annotatedAt IS NOT NULL " +
            "AND (annotationSyncedAt IS NULL OR annotationSyncedAt < annotatedAt) ORDER BY annotatedAt"
    )
    suspend fun unsyncedAnnotations(): List<MpesaTransactionEntity>

    /** Only if the note has not changed again since it was sent. */
    @Query(
        "UPDATE mpesa_transactions SET annotationSyncedAt = :annotatedAt " +
            "WHERE code = :code AND annotatedAt = :annotatedAt"
    )
    suspend fun markAnnotationSynced(code: String, annotatedAt: Long)

    @Query("SELECT * FROM mpesa_unread_days WHERE day = :day")
    suspend fun unreadDay(day: String): MpesaUnreadDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putUnreadDay(day: MpesaUnreadDayEntity)

    /** Adds one to [day]'s count. */
    @Transaction
    suspend fun countUnread(day: String, at: Long) {
        val current = unreadDay(day)
        putUnreadDay(
            MpesaUnreadDayEntity(
                day = day,
                unread = (current?.unread ?: 0) + 1,
                updatedAt = at,
                syncedAt = current?.syncedAt,
            )
        )
    }

    @Query("SELECT * FROM mpesa_unread_days WHERE syncedAt IS NULL OR syncedAt < updatedAt ORDER BY day")
    suspend fun unsyncedUnreadDays(): List<MpesaUnreadDayEntity>

    @Query("UPDATE mpesa_unread_days SET syncedAt = :updatedAt WHERE day = :day AND updatedAt = :updatedAt")
    suspend fun markUnreadDaySynced(day: String, updatedAt: Long)
}
