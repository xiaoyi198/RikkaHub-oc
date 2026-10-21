package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ContextSummaryEntity

@Dao
interface ContextSummaryDAO {
    @Query("SELECT * FROM context_summary WHERE conversation_id = :conversationId ORDER BY created_at, id")
    suspend fun getAll(conversationId: String): List<ContextSummaryEntity>

    @Query("SELECT * FROM context_summary WHERE conversation_id = :conversationId ORDER BY created_at, id")
    fun observe(conversationId: String): Flow<List<ContextSummaryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(summary: ContextSummaryEntity)

    @Query("DELETE FROM context_summary WHERE conversation_id = :conversationId")
    suspend fun deleteOfConversation(conversationId: String)
}
