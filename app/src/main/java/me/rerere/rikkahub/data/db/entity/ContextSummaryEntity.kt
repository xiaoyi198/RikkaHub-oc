package me.rerere.rikkahub.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "context_summary",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class,
        parentColumns = ["id"],
        childColumns = ["conversation_id"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("conversation_id")],
)
data class ContextSummaryEntity(
    @PrimaryKey val id: String,
    @ColumnInfo("conversation_id") val conversationId: String,
    val payload: String,
    @ColumnInfo("created_at") val createdAt: Long,
)
