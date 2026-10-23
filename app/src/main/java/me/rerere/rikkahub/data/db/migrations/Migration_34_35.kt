package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_34_35 = object : Migration(34, 35) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `context_prune_batch` (`id` TEXT NOT NULL, " +
                "`conversation_id` TEXT NOT NULL, `payload` TEXT NOT NULL, `created_at` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`), FOREIGN KEY(`conversation_id`) REFERENCES `ConversationEntity`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_context_prune_batch_conversation_id` ON `context_prune_batch` (`conversation_id`)")
    }
}
