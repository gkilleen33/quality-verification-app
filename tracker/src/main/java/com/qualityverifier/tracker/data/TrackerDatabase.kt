package com.qualityverifier.tracker.data

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * PesaLog's own database on the phone, separate from Kagua's and Fundi Bora's: a different
 * app, so a different file in a different sandbox. Version 1; a later change adds a Migration
 * here and its exported schema under tracker/schemas, as :core's AppDatabase does.
 */
@Database(
    entities = [TransactionEntity::class, UnreadDayEntity::class, CountedUnreadEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun dao(): TrackerDao

    companion object {
        const val NAME = "pesalog.db"
    }
}
