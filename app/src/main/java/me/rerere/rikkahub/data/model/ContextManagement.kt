package me.rerere.rikkahub.data.model

import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

/** Global opt-in. Disabling this bypasses all enhanced context projection and tools. */
@Serializable
data class ContextManagementSettings(
    val enabled: Boolean = false,
    val autoCompress: Boolean = true,
    val triggerPercent: Int = 80,
    val targetPercent: Int = 65,
    // Zero means adapt to the selected model's window.
    val compressionChunkTokens: Int = 0,
    val recentTokens: Int = 0,
    val summaryTokens: Int = 0,
    val summaryModelId: Uuid? = null,
    val historyTools: Boolean = true,
    val recallTokens: Int = 4096,
    val prune: Boolean = false,
    val pruneProtectTokens: Int = 0,
    val pruneMinimumTokens: Int = 0,
    val pruneProtectedTools: List<String> = emptyList(),
) {
    fun normalized() = copy(
        triggerPercent = triggerPercent.coerceIn(50, 95),
        targetPercent = targetPercent.coerceIn(10, triggerPercent.coerceIn(50, 95) - 5),
        compressionChunkTokens = compressionChunkTokens.coerceIn(0, 1_000_000),
        recentTokens = recentTokens.coerceIn(0, 1_000_000),
        summaryTokens = summaryTokens.coerceIn(0, 32_768),
        recallTokens = recallTokens.coerceIn(256, 32_768),
        pruneProtectTokens = pruneProtectTokens.coerceIn(0, 1_000_000),
        pruneMinimumTokens = pruneMinimumTokens.coerceIn(0, 1_000_000),
        pruneProtectedTools = pruneProtectedTools.map(String::trim).filter(String::isNotEmpty).distinct(),
    )
}

/** References original message parts, never copies or replaces the conversation transcript. */
@Serializable
data class ContextSource(
    val messageId: String,
    val fromPart: Int,
    val toPart: Int,
    val fingerprint: String,
)

@Serializable
data class ContextSummary(
    val id: String = Uuid.random().toString(),
    val conversationId: String,
    val text: String,
    val sources: List<ContextSource>,
    val createdAt: Long = System.currentTimeMillis(),
    val tokensBefore: Int,
    val tokensAfter: Int,
)
