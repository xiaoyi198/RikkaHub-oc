package me.rerere.rikkahub.data.ai.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextPruneBatch
import me.rerere.rikkahub.data.model.ContextSummary
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextManagementPolicyTest {
    private val model = Model(modelId = "test", contextLength = 256_000)
    private val config = ContextManagementSettings(enabled = true)
    private val summaries = mutableListOf<ContextSummary>()
    private val store = object : ContextSummaryStore {
        override suspend fun getPruneBatches(conversationId: String) = emptyList<ContextPruneBatch>()
        override suspend fun getAll(conversationId: String) = summaries.toList()
        override suspend fun index(conversationId: String, messages: List<UIMessage>) = Unit
    }

    private fun manager(messages: List<UIMessage>) = ManagedContext(
        conversationId = "c", settings = config, window = model.contextLength, maxOutput = null,
        tools = emptyList(), history = ContextHistory(messages), repository = store,
        status = MutableStateFlow(null), persist = {
            summaries.add(it)
            true
        },
        summarize = { _, _ -> "summary ".repeat(1500) },
        persistPruning = { _, _ -> false },
    )

    private fun build(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model))

    private fun history(turns: Int) = buildList {
        repeat(turns) {
            add(UIMessage.user("task $it"))
            add(UIMessage.assistant("x".repeat(54_000)))
        }
        add(UIMessage.user("continue"))
    }

    @Test
    fun `automatic compression leaves headroom and preserves earlier independent summaries`() = runTest {
        val original = history(12)
        val budget = contextBudget(model.contextLength, null, config)
        val first = manager(original).prepare(original, build = ::build)
        assertTrue(first.messages.sumOf(::estimateContextMessage) <= budget.target)
        val saved = summaries.toList()
        assertTrue(saved.isNotEmpty())

        val next = original + UIMessage.assistant("y".repeat(54_000)) + UIMessage.user("next")
        manager(next).prepare(next, build = ::build)
        assertEquals(saved, summaries)

        val later = next + UIMessage.assistant("z".repeat(210_000)) + UIMessage.user("continue again")
        val result = manager(later).prepare(later, build = ::build)
        assertTrue(result.messages.sumOf(::estimateContextMessage) <= budget.target)
        val active = projectContext(contextUnits(later), summaries).mapNotNull { it.summary }
        assertTrue(active.size > saved.size)
        assertTrue(active.containsAll(saved))
        assertEquals(original, later.take(original.size))
    }

    @Test
    fun `returned usage can gradually correct an overestimate before another compression`() = runTest {
        val messages = history(11)
        val managed = manager(messages)
        managed.prepare(messages, build = ::build)
        val generated = UIMessage.assistant("y".repeat(30_000)).copy(
            lastUsage = TokenUsage(promptTokens = 150_000, completionTokens = 7500)
        )
        managed.recordUsage(generated, newPartsFrom = 0)
        val next = messages + generated + UIMessage.user("next")
        assertTrue(next.sumOf(::estimateContextMessage) > contextBudget(model.contextLength, null, config).trigger)
        managed.prepare(next, build = ::build)
        assertTrue(summaries.isEmpty())
    }

    @Test
    fun `manual compression with no new raw history does not rewrite an existing summary`() = runTest {
        val messages = history(2)
        val sources = contextUnits(messages).dropLast(1).map { it.source }
        summaries.add(ContextSummary(
            conversationId = "c",
            text = "existing summary",
            sources = sources,
            tokensBefore = 36_000,
            tokensAfter = 6,
        ))
        val failure = runCatching {
            manager(messages).prepare(messages, build = ::build, force = true, keepRecentMessages = 1)
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("没有可压缩的历史"))
        assertEquals(1, summaries.size)
    }

    @Test
    fun `advanced budgets deserialize old settings and clamp invalid combinations`() {
        val old = JsonInstant.decodeFromString<ContextManagementSettings>("{\"enabled\":true}")
        assertEquals(65, old.targetPercent)
        assertEquals(0, old.compressionChunkTokens)
        val defaults = contextBudget(model.contextLength, null, old)
        assertEquals(166_400, defaults.target)
        assertEquals(defaults.input / 3, defaults.chunk)
        assertEquals(defaults.input / 12, defaults.summary)
        val largerWindow = contextBudget(1_000_000, null, old)
        assertTrue(largerWindow.chunk > defaults.chunk)
        assertTrue(largerWindow.summary > defaults.summary)
        val normalized = old.copy(triggerPercent = 50, targetPercent = 90, compressionChunkTokens = -1).normalized()
        assertEquals(45, normalized.targetPercent)
        assertEquals(0, normalized.compressionChunkTokens)
        val custom = contextBudget(4096, 400, old.copy(compressionChunkTokens = 1000, targetPercent = 55))
        assertEquals(1000, custom.chunk)
        assertEquals(2252, custom.target)
        val largeManualChunk = contextBudget(
            1_000_000,
            null,
            old.copy(compressionChunkTokens = 700_000)
        )
        assertEquals(700_000, largeManualChunk.chunk)
        assertEquals(
            normalized,
            JsonInstant.decodeFromString<ContextManagementSettings>(JsonInstant.encodeToString(normalized))
        )
    }

    @Test
    fun `automatic budgets scale with available input without fixed token caps`() {
        val budget = contextBudget(1_000_000, null, config)
        assertEquals(budget.input / 3, budget.chunk)
        assertEquals(budget.input / 5, budget.recent)
        assertEquals(budget.input / 12, budget.summary)
        val prune = contextPruneBudget(budget.input, config)
        assertEquals(budget.input * 2 / 5, prune.protect)
        assertEquals(budget.input / 5, prune.minimum)
    }
}
