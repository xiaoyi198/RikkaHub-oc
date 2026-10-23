package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextPruneEntry
import me.rerere.rikkahub.data.model.ContextSource

data class PruneBudget(val protect: Int, val minimum: Int)

fun contextPruneBudget(input: Int, settings: ContextManagementSettings): PruneBudget {
    val maximum = (input / 2).coerceAtLeast(1)
    return PruneBudget(
        protect = (settings.pruneProtectTokens.takeIf { it > 0 } ?: (input.toLong() * 2 / 5).toInt())
            .coerceIn(1, maximum),
        minimum = (settings.pruneMinimumTokens.takeIf { it > 0 } ?: (input / 5))
            .coerceIn(1, maximum),
    )
}

data class PrunedContext(
    val items: List<ContextItem>,
    val sources: Set<ContextSource> = emptySet(),
    val tokensSaved: Int = 0,
    val toolCount: Int = 0,
    val entries: List<ContextPruneEntry> = emptyList(),
    val newEntries: List<ContextPruneEntry> = emptyList(),
    val candidateTokens: Int = 0,
)

/** Apply saved decisions first; only newly eligible results contribute to the next batch. */
fun pruneContext(
    items: List<ContextItem>,
    originals: List<UIMessage>,
    settings: ContextManagementSettings,
    inputBudget: Int,
    recallAvailable: Boolean,
    consumedMessageId: String? = null,
    consumedBeforePart: Int = 0,
    originalItems: List<ContextItem> = items,
    ignoreMinimum: Boolean = false,
    committed: List<ContextPruneEntry> = emptyList(),
): PrunedContext {
    if (!settings.enabled || !settings.prune || !recallAvailable) return PrunedContext(items)
    val budget = contextPruneBudget(inputBudget, settings)
    val users = originals.indices.filter { originals[it].role == MessageRole.USER && !originals[it].isSynthetic }
    val recentStart = users.takeLast(2).firstOrNull() ?: 0
    val recentIds = originals.drop(recentStart).mapTo(HashSet()) { it.id.toString() }
    val currentId = originals.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id?.toString()
    val savedEntries = committed.associateBy { it.messageId to it.toolCallId }
    val visible = items.filter { it.summary == null }.flatMap { item ->
        item.message.getTools().map { item.message.id.toString() to it.toolCallId }
    }.toSet()
    val candidates = mutableListOf<ContextPruneEntry>()
    var protectedTokens = 0L
    for (item in originalItems.asReversed()) {
        if (item.summary != null) break
        val messageId = item.message.id.toString()
        if (item.message.role != MessageRole.ASSISTANT) continue
        val source = item.sources.singleOrNull() ?: continue
        for (partIndex in item.message.parts.indices.reversed()) {
            val tool = item.message.parts[partIndex] as? UIMessagePart.Tool ?: continue
            val key = messageId to tool.toolCallId
            if (key !in visible || key in savedEntries || !isPrunableTool(tool, settings)) continue
            val consumedInRun = messageId == currentId && messageId == consumedMessageId &&
                source.fromPart + partIndex < consumedBeforePart
            if (messageId in recentIds && !consumedInRun) continue
            val outputTokens = tool.output.sumOf { estimateContextText((it as UIMessagePart.Text).text).toLong() }
            if (protectedTokens < budget.protect) {
                protectedTokens += outputTokens
                continue
            }
            val marker = "[Old tool result content cleared. Original preserved; " +
                "use history_read(id=\"$messageId\", tool_call_id=\"${tool.toolCallId}\") to recover it.]"
            val saving = outputTokens - estimateContextText(marker)
            if (saving > 0) {
                candidates.add(ContextPruneEntry(
                    messageId,
                    tool.toolCallId,
                    marker,
                    saving.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                ))
            }
        }
    }
    val candidateTokens = candidates.sumOf { it.tokensSaved.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    val newEntries = candidates.takeIf { ignoreMinimum || candidateTokens >= budget.minimum }.orEmpty()
    val replacements = savedEntries + newEntries.associateBy { it.messageId to it.toolCallId }
    val sources = mutableSetOf<ContextSource>()
    val applied = mutableListOf<ContextPruneEntry>()
    val projected = items.map { item ->
        if (item.summary != null) return@map item
        val parts = item.message.parts.map { part ->
            val tool = part as? UIMessagePart.Tool
            val entry = tool?.let {
                replacements[item.message.id.toString() to it.toolCallId]?.takeIf { isPrunableTool(tool, settings) }
            }
            if (entry == null) {
                part
            } else {
                applied.add(entry)
                sources.addAll(item.sources)
                checkNotNull(tool).copy(output = listOf(UIMessagePart.Text(entry.marker)))
            }
        }
        if (parts == item.message.parts) item else item.copy(message = item.message.copy(parts = parts))
    }
    return PrunedContext(
        items = projected,
        sources = sources,
        tokensSaved = applied.sumOf { it.tokensSaved.toLong() }.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        toolCount = applied.size,
        entries = applied,
        newEntries = newEntries,
        candidateTokens = candidateTokens,
    )
}

fun isPrunableTool(tool: UIMessagePart.Tool, settings: ContextManagementSettings): Boolean =
    tool.isExecuted && tool.toolName !in setOf("skill", "use_skill", "ask_user") &&
        tool.toolName !in settings.pruneProtectedTools &&
        tool.approvalState != ToolApprovalState.Pending &&
        tool.approvalState !is ToolApprovalState.Denied && tool.approvalState !is ToolApprovalState.Answered &&
        tool.output.all { it is UIMessagePart.Text }
