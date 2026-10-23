package me.rerere.rikkahub.data.ai.context

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextPruneBatch
import me.rerere.rikkahub.data.model.ContextSummary
import kotlin.math.ceil

/** One run's canonical branch, shared with its history tools. Never read a stale database conversation. */
class ContextHistory(messages: List<UIMessage>) {
    var messages: List<UIMessage> = messages
        private set

    fun update(messages: List<UIMessage>) {
        this.messages = messages
    }
}

interface ContextSummaryStore {
    suspend fun getAll(conversationId: String): List<ContextSummary>
    suspend fun index(conversationId: String, messages: List<UIMessage>)
    suspend fun getPruneBatches(conversationId: String): List<ContextPruneBatch>
}

class ManagedContext(
    private val conversationId: String,
    private val settings: ContextManagementSettings,
    private val window: Int,
    private val maxOutput: Int?,
    private val tools: List<Tool>,
    private val history: ContextHistory,
    private val repository: ContextSummaryStore,
    private val status: MutableStateFlow<String?>,
    private val persist: suspend (ContextSummary) -> Boolean,
    private val summarize: suspend (String, Int) -> String,
    private val persistPruning: suspend (ContextPruneBatch, List<UIMessage>) -> Boolean,
    private val inspect: (StepRequest, PrunedContext, List<ContextItem>) -> Unit = { _, _, _ -> },
) {
    private var summaries: List<ContextSummary>? = null
    private var measuredFactor = 1.0
    private var lastEstimate = 0
    private var latestGeneratedMessageId: String? = null
    private var latestGeneratedFrom = 0

    fun recordUsage(message: UIMessage, newPartsFrom: Int) {
        latestGeneratedMessageId = message.id.toString()
        latestGeneratedFrom = newPartsFrom
        val input = message.lastRequestUsage()?.promptTokens ?: return
        if (lastEstimate > 0 && input > 0) {
            val measured = (input.toDouble() / lastEstimate).coerceAtLeast(0.5)
            // Raise immediately for safety; correct overestimation gradually instead of ignoring it.
            measuredFactor = if (measured >= measuredFactor) measured else measuredFactor * 0.7 + measured * 0.3
        }
    }

    suspend fun prepare(
        messages: List<UIMessage>,
        build: suspend (List<UIMessage>) -> StepRequest,
        force: Boolean = false,
        keepRecentMessages: Int? = null,
        additionalPrompt: String = "",
    ): StepRequest {
        history.update(messages)
        val recallAvailable = tools.any { it.name == "history_read" }
        if (recallAvailable) repository.index(conversationId, messages)
        // Explicit user-created legacy checkpoints still define the beginning of active context.
        val units = contextUnits(messages.limitContext(0))
        // Keep pruning decisions tied to canonical ranges. Summarizing part of an approved
        // batch must not bring the remaining cleared results back into the next request.
        val originalItems = units.map { ContextItem(it.message, listOf(it.source)) }
        val budget = contextBudget(window, maxOutput, settings)
        if (summaries == null) summaries = repository.getAll(conversationId)
        val batches = if (settings.prune && recallAvailable) repository.getPruneBatches(conversationId) else emptyList()
        var changed = false
        var automaticCompression = false
        var attempts = 0
        while (true) {
            val items = projectContext(units, checkNotNull(summaries), recallAvailable)
            fun prune(ignoreMinimum: Boolean = false) = pruneContext(
                items = items,
                originals = messages,
                settings = settings,
                inputBudget = budget.input,
                recallAvailable = recallAvailable,
                consumedMessageId = latestGeneratedMessageId,
                consumedBeforePart = latestGeneratedFrom,
                originalItems = originalItems,
                ignoreMinimum = ignoreMinimum,
                committed = batches.flatMap { it.entries },
            )
            var pruned = prune()
            var step = build(pruned.items.map { it.message })
            var estimated = estimateStep(step)
            var underPressure = false
            // Batch size only avoids frequent prefix changes. It must not force a lossy summary
            // while safe, recoverable tool results could still bring the request below the trigger.
            if (estimated > budget.trigger || (automaticCompression && estimated > budget.target)) {
                val smallerBatch = prune(ignoreMinimum = true)
                if (smallerBatch.newEntries != pruned.newEntries) {
                    pruned = smallerBatch
                    underPressure = true
                    step = build(pruned.items.map { it.message })
                    estimated = estimateStep(step)
                }
            }
            val tailStart = tailStart(items, budget.recent, keepRecentMessages)
            val firstRaw = items.take(tailStart).indexOfFirst { it.summary == null }
            if (settings.autoCompress && estimated > budget.trigger) automaticCompression = true
            val due = automaticCompression && estimated > budget.target
            val requested = force && (!changed || firstRaw >= 0)
            val canReduce = tailStart > 0 && (firstRaw >= 0 || tailStart > 1)
            if (force && !changed && firstRaw < 0 && keepRecentMessages != null) {
                error("没有可压缩的历史。请减少保留的近期消息数。")
            }
            // The protected tail or fixed prompts can make the target unreachable. Once safely
            // below the trigger, keep them rather than repeatedly rewriting a lone summary.
            if ((!requested && !due) || (changed && !canReduce && estimated <= budget.trigger)) {
                if (estimated > budget.input) error("上下文超过可用预算。请启用自动压缩、手动压缩，或调整模型容量与近期保留预算。")
                if (force && !changed) error("没有可压缩的历史。请减少保留的近期消息数。")
                if (pruned.newEntries.isNotEmpty()) {
                    val batch = ContextPruneBatch(
                        conversationId = conversationId,
                        underPressure = underPressure,
                        entries = pruned.newEntries,
                    )
                    check(persistPruning(batch, messages)) { "裁剪期间对话内容发生变化，请重新发送。" }
                    val saved = batch.entries.sumOf { it.tokensSaved.toLong() }
                    status.value = "本次裁剪 ${batch.entries.size} 个旧工具结果，约释放 $saved tokens"
                }
                lastEstimate = estimateStep(step, calibrated = false)
                inspect(step, pruned, items)
                return step
            }
            check(tailStart > 0) { "最近一轮内容已经超过上下文预算，无法在保留完整工具结果的情况下继续。请调整保留预算或使用更大上下文的模型。" }
            check(attempts++ < 32) { "本次压缩次数已达上限，请手动分批压缩历史。" }
            // Append summaries for new raw ranges before merging existing summaries.
            val start = firstRaw.takeIf { it >= 0 } ?: 0
            val candidate = mutableListOf<ContextItem>()
            var candidateTokens = 0
            val chunkBudget = budget.chunk
            for (index in start until tailStart) {
                val item = items[index]
                if (firstRaw >= 0 && item.summary != null && candidateTokens >= 256) break
                val size = estimateContextMessage(item.message)
                if (candidate.isNotEmpty() && candidateTokens + size > chunkBudget &&
                    candidateTokens >= 256 &&
                    (candidate.size > 1 || candidate.first().summary == null)
                ) {
                    break
                }
                candidate.add(item)
                candidateTokens += size
            }
            check(candidate.isNotEmpty()) { "没有可以安全压缩的消息范围。" }
            check(candidate.none { item -> item.message.getTools().any { !it.isExecuted } }) {
                "请先处理尚未完成的工具调用，再压缩相关历史。"
            }
            status.value = "正在压缩上下文（估算 $estimated tokens）…"
            // Summarize the unpruned projection so archived observations are not lost from summaries.
            // Build still includes extracted document text and OCR.
            val preparedCandidate = build(candidate.map { it.message }).messages
                .filterNot { it.role == me.rerere.ai.core.MessageRole.SYSTEM }
            val transcript = preparedCandidate.joinToString("\n\n") { contextTranscript(it) }
            val actualCandidateTokens = preparedCandidate.sumOf(::estimateContextMessage)
            val text = summarize(
                transcript +
                    if (additionalPrompt.isBlank()) "" else "\n\nAdditional user instructions: $additionalPrompt",
                budget.summary,
            ).trim()
            check(text.isNotBlank()) { "压缩模型返回了空摘要。" }
            val summary = ContextSummary(
                conversationId = conversationId,
                text = text,
                sources = candidate.flatMap { it.sources },
                tokensBefore = actualCandidateTokens,
                tokensAfter = estimateContextText(text),
            )
            check(summary.tokensAfter + 80 < actualCandidateTokens * 0.9) {
                "摘要没有释放足够的上下文，已保留原文。请减少摘要长度或调整近期保留预算。"
            }
            check(persist(summary)) { "压缩期间对话内容发生变化，摘要未应用。" }
            summaries = checkNotNull(summaries) + summary
            changed = true
            status.value = "上下文已压缩：约 ${summary.tokensBefore} → ${summary.tokensAfter} tokens"
        }
    }

    private fun estimateStep(step: StepRequest, calibrated: Boolean = true): Int {
        val schemas = tools.sumOf { estimateContextText(it.name + it.description + it.parameters().toString()) + 24 }
        val estimate = step.messages.sumOf(::estimateContextMessage).toLong() + schemas
        return ceil(estimate * if (calibrated) measuredFactor else 1.0).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }

    private fun tailStart(items: List<ContextItem>, recent: Int, keepMessages: Int?): Int {
        if (keepMessages != null) {
            if (keepMessages <= 0) return items.size
            val ids = history.messages.takeLast(keepMessages).mapTo(HashSet()) { it.id.toString() }
            return items.indexOfFirst { item -> item.sources.any { it.messageId in ids } }.takeIf { it >= 0 }
                ?: items.size
        }
        if (items.isEmpty()) return 0
        val current = history.messages.lastOrNull()?.takeIf { it.role == MessageRole.ASSISTANT }
        val freshFrom = if (current?.id.toString() == latestGeneratedMessageId) latestGeneratedFrom else 0
        val protectedMedia = if (current == null) {
            null
        } else {
            items.indexOfFirst { item ->
                item.sources.any { it.messageId == current.id.toString() && it.toPart > freshFrom } &&
                    item.message.getTools().any { tool ->
                        tool.output.any {
                            it is UIMessagePart.Image || it is UIMessagePart.Video || it is UIMessagePart.Audio
                        }
                    }
            }.takeIf { it >= 0 }
        }
        var index = items.lastIndex
        var total = estimateContextMessage(items[index].message)
        // One enormous completed tool result must be compressible even when it is the newest item.
        // The canonical result stays in history and the complete call/result pair enters the summary together.
        val lastTool = items[index].message.parts.lastOrNull() as? UIMessagePart.Tool
        val hasMedia = lastTool?.output?.any {
            it is UIMessagePart.Image || it is UIMessagePart.Video || it is UIMessagePart.Audio
        } == true
        if (total > recent && lastTool?.isExecuted == true && !hasMedia) {
            return minOf(items.size, protectedMedia ?: items.size)
        }
        while (index > 0) {
            val previous = items[index - 1]
            if (previous.summary != null) break
            val next = total + estimateContextMessage(previous.message)
            if (next > recent) break
            total = next
            index--
        }
        // Prefer a user boundary, while allowing a single huge tool run to split at complete Tool parts.
        val user = (index..items.lastIndex).firstOrNull { items[it].message.role == MessageRole.USER }
        return minOf(user ?: index, protectedMedia ?: items.size)
    }
}

/** Strictly classify size errors; authentication, rate limits and transient network errors use normal retry. */
fun isContextOverflow(error: Throwable): Boolean {
    if (error is CancellationException) return false
    return generateSequence(error) { it.cause }.take(8).any {
        val text = it.message.orEmpty().lowercase()
        listOf(
            "context_length_exceeded",
            "context_window_exceeded",
            "maximum context length",
            "prompt is too long",
            "input is too long",
            "exceeds the context window",
            "exceeds the maximum number of tokens",
            "input token count exceeds",
        ).any(text::contains)
    }
}
