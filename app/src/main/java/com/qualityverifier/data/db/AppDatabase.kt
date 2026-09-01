package com.qualityverifier.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SessionEntity::class,
        MessageEntity::class,
        AttachmentEntity::class,
        PendingRemoteDeleteEntity::class,
    ],
    version = 5,
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

        /**
         * Adds `sessions.previousSessionId` and `sessions.intakeAnswers`, which together
         * let one assessment lead into the next: the answers carry forward so the second
         * piece asks only for its price, and the link back makes the two comparable.
         *
         * Both are null for every row that already exists, which is the right answer —
         * an assessment recorded before this was a single piece on its own.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN previousSessionId TEXT")
                db.execSQL("ALTER TABLE sessions ADD COLUMN intakeAnswers TEXT")
            }
        }

        /**
         * Adds `pending_remote_deletes`.
         *
         * A deletion has to survive being made offline: the local row is gone
         * immediately, so the only record that the server still needs telling is this
         * table. Losing it would mean the server keeping a copy the customer believes was
         * deleted seven days later.
         */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS pending_remote_deletes (" +
                        "sessionId TEXT NOT NULL PRIMARY KEY, requestedAt INTEGER NOT NULL)"
                )
            }
        }

        val MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
    }
}
