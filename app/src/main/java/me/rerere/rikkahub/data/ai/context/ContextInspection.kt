package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.ContextManagementSettings
import kotlin.uuid.Uuid

/** Lightweight diagnostics from an already prepared request, never a second preparation pass. */
data class ContextInspection(
    val requestId: Uuid = Uuid.random(),
    val createdAt: Long = System.currentTimeMillis(),
    val messageIds: List<Uuid>,
    val assistant: Assistant,
    val settings: ContextManagementSettings,
    val modelId: Uuid,
    val modelName: String,
    val breakdown: Map<String, Int>,
    val candidateTokens: Int,
    val minimumTokens: Int,
    val usage: TokenUsage? = null,
)

fun inspectContext(
    step: StepRequest,
    originals: List<UIMessage>,
    assistant: Assistant,
    settings: ContextManagementSettings,
    pruned: PrunedContext = PrunedContext(emptyList()),
    summaryIds: Set<Uuid> = emptySet(),
): ContextInspection {
    val counts = linkedMapOf<String, Int>()
    fun add(label: String, tokens: Int) {
        counts[label] = (counts[label] ?: 0) + tokens
    }
    fun count(part: UIMessagePart, label: String) {
        when (part) {
            is UIMessagePart.Tool -> {
                add("工具调用", estimateContextText(part.toolName + part.input) + 16)
                part.output.forEach {
                    add("工具结果", 12)
                    count(it, "工具结果")
                }
            }
            is UIMessagePart.Text -> add(label, estimateContextText(part.text))
            is UIMessagePart.Reasoning -> add("助手内容", estimateContextText(part.reasoning))
            else -> add("附件", estimateContextMessage(UIMessage.user("").copy(parts = listOf(part))) - 12)
        }
    }
    step.messages.forEach { message ->
        val label = when {
            message.id in summaryIds -> "历史摘要"
            message.role == MessageRole.SYSTEM -> "系统指令"
            message.role == MessageRole.USER -> "用户内容"
            else -> "助手内容"
        }
        add(label, 12)
        message.parts.forEach { count(it, label) }
    }
    step.params.tools.forEach {
        add("工具定义", estimateContextText(it.name + it.description + it.parameters().toString()) + 24)
    }
    return ContextInspection(
        messageIds = originals.map { it.id },
        assistant = assistant,
        settings = settings,
        modelId = step.params.model.id,
        modelName = step.params.model.displayName,
        breakdown = counts.filterValues { it > 0 },
        candidateTokens = if (pruned.newEntries.isEmpty()) pruned.candidateTokens else 0,
        minimumTokens = contextPruneBudget(
            runCatching { contextBudget(step.params.model.contextLength, assistant.maxTokens, settings).input }
                .getOrDefault(step.params.model.contextLength.coerceAtLeast(1)),
            settings,
        ).minimum,
    )
}
