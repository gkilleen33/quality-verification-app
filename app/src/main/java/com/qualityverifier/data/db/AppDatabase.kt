package com.qualityverifier.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SessionEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 3,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao

    companion object {
        /**
         * Adds `sessions.verdictLevelId`, which the reports list reads to badge an
         * assessment without re-parsing its conversation.
         *
         * Written out rather than falling back to a destructive migration: assessments
         * are the one thing in this app the user cannot recreate, since the furniture
         * is back in a shop somewhere.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN verdictLevelId TEXT")
            }
        }

        /**
         * Adds `sessions.verdictLanguage`. The reports list badges a verdict in the
         * language it was written in, which is the language of the conversation rather
         * than of the handset.
         */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN verdictLanguage TEXT")
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
