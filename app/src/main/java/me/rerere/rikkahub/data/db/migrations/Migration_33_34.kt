package me.rerere.rikkahub.data.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val Migration_33_34 = object : Migration(33, 34) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `workspace_task` (`id` TEXT NOT NULL, `conversation_id` TEXT NOT NULL, `workspace_id` TEXT NOT NULL, `payload` TEXT NOT NULL, PRIMARY KEY(`id`))")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_task_conversation_id` ON `workspace_task` (`conversation_id`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_workspace_task_workspace_id` ON `workspace_task` (`workspace_id`)")
    }
}
