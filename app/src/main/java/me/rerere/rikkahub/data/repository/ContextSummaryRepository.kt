package me.rerere.rikkahub.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.ai.context.ContextSummaryStore
import me.rerere.rikkahub.data.ai.context.contextFingerprint
import me.rerere.rikkahub.data.ai.context.contextTranscript
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.ContextPruneBatchEntity
import me.rerere.rikkahub.data.db.entity.ContextSummaryEntity
import me.rerere.rikkahub.data.db.fts.buildFtsMatchQuery
import me.rerere.rikkahub.data.model.ContextPruneBatch
import me.rerere.rikkahub.data.model.ContextSummary
import me.rerere.rikkahub.utils.JsonInstant
import kotlin.uuid.Uuid

/** Summaries are rebuildable projections. The original message nodes remain the sole transcript. */
class ContextSummaryRepository(private val database: AppDatabase) : ContextSummaryStore {
    private val dao get() = database.contextSummaryDao()
    private val pruneDao get() = database.contextPruneBatchDao()

    override suspend fun getPruneBatches(conversationId: String): List<ContextPruneBatch> =
        pruneDao.getAll(conversationId).mapNotNull(::decodeBatch)

    fun observePruneBatches(conversationId: String) =
        pruneDao.observe(conversationId).map { rows -> rows.mapNotNull(::decodeBatch) }

    suspend fun savePruneBatch(batch: ContextPruneBatch) = pruneDao.insert(ContextPruneBatchEntity(
        id = batch.id,
        conversationId = batch.conversationId,
        payload = JsonInstant.encodeToString(batch),
        createdAt = batch.createdAt,
    ))

    suspend fun clearPruningOfMessage(conversationId: String, messageId: String) = database.withTransaction {
        getPruneBatches(conversationId).forEach { batch ->
            val retained = batch.entries.filterNot { it.messageId == messageId }
            if (retained.size != batch.entries.size) {
                if (retained.isEmpty()) {
                    pruneDao.delete(batch.id)
                } else {
                    savePruneBatch(batch.copy(entries = retained))
                }
            }
        }
    }

    private fun decodeBatch(entity: ContextPruneBatchEntity): ContextPruneBatch? = runCatching {
        JsonInstant.decodeFromString<ContextPruneBatch>(entity.payload).also {
            require(it.id == entity.id && it.conversationId == entity.conversationId)
        }
    }.getOrNull()

    override suspend fun getAll(conversationId: String): List<ContextSummary> = dao.getAll(
        conversationId
    ).mapNotNull(::decode)

    fun observe(conversationId: String) = dao.observe(conversationId).map { rows -> rows.mapNotNull(::decode) }

    suspend fun save(summary: ContextSummary) = dao.insert(ContextSummaryEntity(
        id = summary.id,
        conversationId = summary.conversationId,
        payload = JsonInstant.encodeToString(summary),
        createdAt = summary.createdAt,
    ))

    /** Incremental, full-content index including tool results, scoped to the selected branch. */
    override suspend fun index(conversationId: String, messages: List<UIMessage>) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            // Do not recreate an index after a concurrent conversation deletion.
            val exists = db.query(
                "SELECT 1 FROM ConversationEntity WHERE id = ?",
                arrayOf(conversationId)
            ).use { it.moveToFirst() }
            if (!exists) return@withTransaction
            val stored = mutableMapOf<String, String>()
            db.query(
                "SELECT message_id, fingerprint FROM context_history_fts WHERE conversation_id = ?",
                arrayOf(conversationId),
            ).use { cursor -> while (cursor.moveToNext()) stored[cursor.getString(0)] = cursor.getString(1) }
            val currentIds = messages.mapTo(HashSet()) { it.id.toString() }
            stored.keys.filter { it !in currentIds }.forEach { id ->
                db.execSQL(
                    "DELETE FROM context_history_fts WHERE conversation_id = ? AND message_id = ?",
                    arrayOf(conversationId, id)
                )
            }
            messages.forEach { message ->
                val id = message.id.toString()
                val fingerprint = contextFingerprint(message)
                if (stored[id] == fingerprint) return@forEach
                db.execSQL(
                    "DELETE FROM context_history_fts WHERE conversation_id = ? AND message_id = ?",
                    arrayOf(conversationId, id)
                )
                db.execSQL(
                    "INSERT INTO context_history_fts(text, message_id, conversation_id, fingerprint) VALUES (?, ?, ?, ?)",
                    arrayOf(
                        contextTranscript(message, DocumentAsPromptTransformer::readDocumentContent),
                        id,
                        conversationId,
                        fingerprint
                    ),
                )
            }
        }
    }

    suspend fun search(conversationId: String, query: String, limit: Int): List<HistoryMatch> = withContext(
        Dispatchers.IO
    ) {
        val results = mutableListOf<HistoryMatch>()
        val db = database.openHelper.readableDatabase
        val matchQuery = buildFtsMatchQuery(query) { text ->
            db.query("SELECT simple_query(?)", arrayOf(text)).use {
                if (it.moveToFirst()) it.getString(0).orEmpty() else ""
            }
        } ?: return@withContext results
        db.query(
            "SELECT message_id, fingerprint, simple_snippet(context_history_fts, 0, '', '', '…', 48) " +
                "FROM context_history_fts WHERE text MATCH ? AND conversation_id = ? ORDER BY rank LIMIT ?",
            arrayOf<Any>(matchQuery, conversationId, limit.coerceIn(1, 20)),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                results.add(
                    HistoryMatch(cursor.getString(0), cursor.getString(1), cursor.getString(2))
                )
            }
        }
        results
    }

    private fun decode(entity: ContextSummaryEntity): ContextSummary? =
        runCatching {
            JsonInstant.decodeFromString<ContextSummary>(entity.payload).also {
                require(it.id == entity.id && it.conversationId == entity.conversationId && it.sources.isNotEmpty())
                Uuid.parse(it.id)
            }
        }.getOrNull()
}

data class HistoryMatch(val messageId: String, val fingerprint: String, val snippet: String)
