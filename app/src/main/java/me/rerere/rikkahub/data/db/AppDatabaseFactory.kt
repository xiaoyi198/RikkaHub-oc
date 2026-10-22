package me.rerere.rikkahub.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import me.rerere.rikkahub.data.db.fts.SimpleDictManager
import me.rerere.rikkahub.data.db.fts.createMessageFtsTables
import me.rerere.rikkahub.data.db.migrations.Migration_11_12
import me.rerere.rikkahub.data.db.migrations.Migration_13_14
import me.rerere.rikkahub.data.db.migrations.Migration_14_15
import me.rerere.rikkahub.data.db.migrations.Migration_15_16
import me.rerere.rikkahub.data.db.migrations.Migration_27_28
import me.rerere.rikkahub.data.db.migrations.Migration_30_31
import me.rerere.rikkahub.data.db.migrations.Migration_32_33
import me.rerere.rikkahub.data.db.migrations.Migration_33_34
import me.rerere.rikkahub.data.db.migrations.Migration_6_7

/** Shared schema, migrations and extensions for the app and staged backup validation. */
internal object AppDatabaseFactory {
    fun create(context: Context, name: String = SQLiteConfiguration.DATABASE_NAME): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name)
            .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .addMigrations(
                Migration_6_7,
                Migration_11_12,
                Migration_13_14,
                Migration_14_15,
                Migration_15_16,
                Migration_27_28,
                Migration_30_31,
                Migration_32_33,
                Migration_33_34,
            )
            .addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    val dictDir = SimpleDictManager.extractDict(context)
                    val cursor = db.query("SELECT jieba_dict(?)", arrayOf(dictDir.absolutePath))
                    cursor.use {
                        if (it.moveToFirst()) {
                            val result = it.getString(0)
                            val success = result?.trimEnd('/') == dictDir.absolutePath.trimEnd('/')
                            if (!success) {
                                android.util.Log.e(
                                    "DataSourceModule",
                                    "jieba_dict failed: $result, path=${dictDir.absolutePath}"
                                )
                            }
                        }
                    }
                    createMessageFtsTables(db)
                    // Full text and tool results for enhanced context recall; populated only on opt-in.
                    db.execSQL(
                        "CREATE VIRTUAL TABLE IF NOT EXISTS context_history_fts USING fts5(" +
                            "text, message_id UNINDEXED, conversation_id UNINDEXED, fingerprint UNINDEXED, tokenize = 'simple')"
                    )
                    db.execSQL(
                        "CREATE TRIGGER IF NOT EXISTS delete_context_history AFTER DELETE ON ConversationEntity " +
                            "BEGIN DELETE FROM context_history_fts WHERE conversation_id = OLD.id; END"
                    )
                }
            })
            .openHelperFactory(SQLiteConfiguration.openHelperFactory(context))
            .build()
}
