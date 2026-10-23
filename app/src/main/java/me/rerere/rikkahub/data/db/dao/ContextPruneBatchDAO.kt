package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import me.rerere.rikkahub.data.db.entity.ContextPruneBatchEntity

@Dao
interface ContextPruneBatchDAO {
    @Query("SELECT * FROM context_prune_batch WHERE conversation_id = :conversationId ORDER BY created_at, id")
    suspend fun getAll(conversationId: String): List<ContextPruneBatchEntity>

    @Query("SELECT * FROM context_prune_batch WHERE conversation_id = :conversationId ORDER BY created_at, id")
    fun observe(conversationId: String): Flow<List<ContextPruneBatchEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(batch: ContextPruneBatchEntity)

    @Query("DELETE FROM context_prune_batch WHERE id = :id")
    suspend fun delete(id: String)
}
