package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "workspace_task", indices = [Index("conversation_id"), Index("workspace_id")])
data class WorkspaceTaskEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    @ColumnInfo("workspace_id") val workspaceId: String,
    val payload: String,
)
