package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ContextManagementSettings
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
)

/**
 * OpenCode-style output pruning, applied only to a request projection. Calls and canonical
 * outputs stay intact. Recomputing from the selected branch makes edits/branch changes safe
 * without persisting markers in messages. Only a worthwhile batch changes the request prefix.
 */
fun pruneContext(
    items: List<ContextItem>,
    originals: List<UIMessage>,
    settings: ContextManagementSettings,
    inputBudget: Int,
    recallAvailable: Boolean,
    consumedMessageId: String? = null,
    consumedBeforePart: Int = 0,
    originalItems: List<ContextItem> = items,
): PrunedContext {
    if (!settings.enabled || !settings.prune || !recallAvailable) return PrunedContext(items)
    val budget = contextPruneBudget(inputBudget, settings)
    val users = originals.indices.filter { originals[it].role == MessageRole.USER && !originals[it].isSynthetic }
    val recentStart = users.takeLast(2).firstOrNull() ?: 0
    val recentIds = originals.drop(recentStart).mapTo(HashSet()) { it.id.toString() }
    val currentId = originals.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }?.id?.toString()
    // Skills and user answers can contain durable instructions rather than disposable observations.
    val protectedTools = setOf("skill", "use_skill", "ask_user") + settings.pruneProtectedTools
    val replacements = mutableMapOf<Pair<ContextSource, Int>, UIMessagePart.Tool>()
    var protectedTokens = 0L
    var saved = 0L
    for (item in originalItems.asReversed()) {
        // Older history is already represented by a summary; never walk across it.
        if (item.summary != null) break
        val messageId = item.message.id.toString()
        if (item.message.role != MessageRole.ASSISTANT) continue
        val source = item.sources.singleOrNull() ?: continue
        for (partIndex in item.message.parts.indices.reversed()) {
            val tool = item.message.parts[partIndex] as? UIMessagePart.Tool ?: continue
            if (!tool.isExecuted || tool.toolName in protectedTools ||
                tool.approvalState == ToolApprovalState.Pending ||
                tool.approvalState is ToolApprovalState.Denied || tool.approvalState is ToolApprovalState.Answered
            ) {
                continue
            }
            // Preserve media/structured results. Only plain text observations are pruned.
            if (tool.output.any { it !is UIMessagePart.Text }) continue
            // Within a long single-turn run, earlier outputs are eligible only after a subsequent
            // model step has consumed them. Every result in the newest tool batch remains intact.
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
            if (saving <= 0) continue
            replacements[source to partIndex] = tool.copy(output = listOf(UIMessagePart.Text(marker)))
            saved += saving
        }
    }
    if (saved < budget.minimum) return PrunedContext(items)
    val sources = mutableSetOf<ContextSource>()
    var visibleSaved = 0L
    var toolCount = 0
    val projected = items.map { item ->
        if (item.summary != null) return@map item
        val source = item.sources.singleOrNull() ?: return@map item
        val parts = item.message.parts.mapIndexed { partIndex, part ->
            replacements[source to partIndex]?.also { replacement ->
                sources.add(source)
                toolCount++
                visibleSaved += (part as UIMessagePart.Tool).output.sumOf {
                    estimateContextText((it as UIMessagePart.Text).text).toLong()
                } - estimateContextText((replacement.output.single() as UIMessagePart.Text).text)
            } ?: part
        }
        if (parts == item.message.parts) item else item.copy(message = item.message.copy(parts = parts))
    }
    return PrunedContext(projected, sources, visibleSaved.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), toolCount)
}
