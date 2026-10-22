package me.rerere.rikkahub.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import me.rerere.rikkahub.data.db.entity.WorkspaceTaskEntity

@Dao
interface WorkspaceTaskDAO {
    @Query("SELECT * FROM workspace_task")
    suspend fun getAll(): List<WorkspaceTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(task: WorkspaceTaskEntity)

    @Query("DELETE FROM workspace_task WHERE id = :id")
    suspend fun delete(id: String)
}
