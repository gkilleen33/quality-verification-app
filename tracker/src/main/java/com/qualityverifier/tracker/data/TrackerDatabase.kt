package com.qualityverifier.tracker.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * PesaLog's own database on the phone, separate from Kagua's and Fundi Bora's: a different
 * app, so a different file in a different sandbox. Each change adds a Migration here and its
 * exported schema under tracker/schemas, as :core's AppDatabase does.
 */
@Database(
    entities = [TransactionEntity::class, UnreadDayEntity::class, CountedUnreadEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class TrackerDatabase : RoomDatabase() {
    abstract fun dao(): TrackerDao

    companion object {
        const val NAME = "pesalog.db"

        /**
         * 0.2.0 keeps the masked number M-Pesa prints on money received (row 32), to send it
         * for the server's masked-number code (V42). Rows 0.1.0 stored keep null: the message
         * itself was never kept, so there is nothing to fill them from.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN counterpartyMasked TEXT")
            }
        }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2)
    }
}
