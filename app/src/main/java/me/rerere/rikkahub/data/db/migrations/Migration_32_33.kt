package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Derived summaries are separate from messages so switching the feature off restores legacy context. */
val Migration_32_33 = object : Migration(32, 33) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `context_summary` (" +
                "`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `payload` TEXT NOT NULL, " +
                "`created_at` INTEGER NOT NULL, PRIMARY KEY(`id`), " +
                "FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_summary_conversation_id` ON `context_summary` (`conversation_id`)")
    }
}
