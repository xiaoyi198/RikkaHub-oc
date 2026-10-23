package me.rerere.rikkahub.data.ai.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.core.Tool
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextPruneBatch
import me.rerere.rikkahub.data.model.ContextSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ManagedContextTest {
    private val model = Model(modelId = "test", contextLength = 4096)
    private val store = object : ContextSummaryStore {
        val summaries = mutableListOf<ContextSummary>()
        val batches = mutableListOf<ContextPruneBatch>()
        override suspend fun getPruneBatches(conversationId: String) = batches.toList()
        override suspend fun getAll(conversationId: String) = summaries.toList()
        override suspend fun index(conversationId: String, messages: List<UIMessage>) = Unit
    }

    private fun manager(
        messages: List<UIMessage>,
        auto: Boolean = true,
        config: ContextManagementSettings =
            ContextManagementSettings(enabled = true, autoCompress = auto, recentTokens = 400),
        tools: List<Tool> = emptyList(),
        history: ContextHistory = ContextHistory(messages),
        persist: suspend (ContextSummary) -> Boolean = {
            store.summaries.add(it)
            true
        },
        summarize: suspend (String, Int) -> String = { _, _ -> "User constraints and completed findings." },
    ) = ManagedContext(
        conversationId = "c",
        settings = config,
        window = model.contextLength,
        maxOutput = 400,
        tools = tools,
        history = history,
        repository = store,
        status = MutableStateFlow(null),
        persist = persist,
        summarize = summarize,
        persistPruning = { batch, _ ->
            store.batches.add(batch)
            true
        },
    )

    private fun build(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model))

    private val recall = listOf(Tool(name = "history_read", description = "Read originals", execute = { emptyList() }))
    private val pruneSettings = ContextManagementSettings(
        enabled = true,
        prune = true,
        recentTokens = 400,
        pruneProtectTokens = 1,
        pruneMinimumTokens = 1,
    )

    private fun toolHistory() = listOf(
        UIMessage.user("old task"),
        UIMessage.assistant("").copy(parts = List(3) { index ->
            UIMessagePart.Tool(
                "call-$index",
                "read",
                "{}",
                listOf(UIMessagePart.Text("SECRET_$index " + "x".repeat(3600)))
            )
        }),
        UIMessage.user("recent one"),
        UIMessage.assistant("done"),
        UIMessage.user("recent two"),
    )

    @Test
    fun `new savings trigger batches and committed results survive a new run and user turn`() = runTest {
        fun tool(index: Int) = UIMessagePart.Tool(
            "step-$index",
            "read",
            "{}",
            listOf(UIMessagePart.Text("x".repeat(900)))
        )
        val user = UIMessage.user("work")
        var current = UIMessage.assistant("").copy(parts = List(4) { tool(it) })
        val config = pruneSettings.copy(autoCompress = false, pruneMinimumTokens = 400)
        val managed = manager(listOf(user, current), config = config, tools = recall)
        managed.recordUsage(current, 3)
        val first = managed.prepare(listOf(user, current), build = ::build)
        assertEquals(1, store.batches.size)
        assertEquals(2, store.batches.single().entries.size)

        current = current.copy(parts = current.parts + tool(4))
        managed.recordUsage(current, 4)
        val second = managed.prepare(listOf(user, current), build = ::build)
        assertEquals(1, store.batches.size)
        assertEquals(
            first.messages.flatMap { it.getTools() }.take(3),
            second.messages.flatMap { it.getTools() }.take(3)
        )

        current = current.copy(parts = current.parts + tool(5))
        managed.recordUsage(current, 5)
        managed.prepare(listOf(user, current), build = ::build)
        assertEquals(2, store.batches.size)
        assertEquals(2, store.batches.last().entries.size)

        val nextTurn = listOf(user, current, UIMessage.user("continue"))
        val restored = manager(nextTurn, config = config, tools = recall).prepare(nextTurn, build = ::build)
        assertEquals(4, restored.messages.flatMap { it.getTools() }.count { it.output.toString().contains("cleared") })
        assertEquals(2, store.batches.size)
    }

    @Test
    fun `pruning runs before summary and can work with automatic summary disabled`() = runTest {
        for (auto in listOf(true, false)) {
            val messages = toolHistory()
            var calls = 0
            val result = manager(
                messages,
                config = pruneSettings.copy(autoCompress = auto),
                tools = recall,
                summarize = { _, _ ->
                    calls++
                    "summary"
                }
            ).prepare(messages, build = ::build)
            assertEquals(0, calls)
            assertTrue(store.summaries.isEmpty())
            assertTrue(result.messages.flatMap { it.getTools() }.first().output.toString().contains("history_read"))
            assertTrue(result.messages.sumOf(::estimateContextMessage) < 3200)
            assertTrue(messages[1].getTools().first().output.toString().contains("SECRET_0"))
        }
    }

    @Test
    fun `small pruning batches defer automatic summaries during a tool run`() = runTest {
        fun tool(index: Int, chars: Int = 900) = UIMessagePart.Tool(
            "call-$index",
            "read",
            "{}",
            listOf(UIMessagePart.Text("x".repeat(chars)))
        )
        val user = UIMessage.user("work")
        var current = UIMessage.assistant("").copy(parts = List(7) { tool(it) })
        val config = ContextManagementSettings(enabled = true, prune = true, recentTokens = 400)
        val managed = manager(listOf(user, current), config = config, tools = recall)
        val budget = contextBudget(model.contextLength, 400, config)
        repeat(5) { index ->
            val consumed = current.parts.size
            current = current.copy(parts = current.parts + tool(consumed, if (index == 0) 3000 else 900))
            managed.recordUsage(current, newPartsFrom = consumed)
            val messages = listOf(user, current)
            val result = managed.prepare(messages, build = ::build)
            assertTrue("Pruning should avoid summary on step $index", store.summaries.isEmpty())
            val projected = result.messages.flatMap { it.getTools() }
            assertEquals(current.getTools().size, projected.size)
            assertTrue(projected.first().output.toString().contains("Old tool result content cleared"))
            assertEquals(current.getTools().last(), projected.last())
            assertTrue(result.messages.sumOf(::estimateContextMessage) <= budget.trigger)
            assertTrue(current.getTools().none { it.output.toString().contains("Old tool result content cleared") })
        }
    }

    @Test
    fun `small pruning batches also keep requests within budget with automatic summaries disabled`() = runTest {
        val config = ContextManagementSettings(
            enabled = true,
            prune = true,
            autoCompress = false,
            pruneProtectTokens = 1,
            pruneMinimumTokens = 1000,
        )
        val messages = listOf(
            UIMessage.user("old task"),
            UIMessage.assistant("").copy(parts = listOf(
                UIMessagePart.Tool("old", "read", "{}", listOf(UIMessagePart.Text("x".repeat(1500)))),
                UIMessagePart.Tool("keep", "read", "{}", listOf(UIMessagePart.Text("x".repeat(1500)))),
            )),
            UIMessage.user("recent one"),
            UIMessage.assistant("details ".repeat(850)),
            UIMessage.user("recent two"),
        )
        val result = manager(messages, config = config, tools = recall).prepare(messages, build = ::build)
        assertTrue(store.summaries.isEmpty())
        assertTrue(result.messages.flatMap { it.getTools() }.first().output.toString().contains("history_read"))
        assertEquals(messages.last(), result.messages.last())
    }

    @Test
    fun `existing summaries do not starve small pruning batches on later runs`() = runTest {
        val config = pruneSettings.copy(pruneMinimumTokens = 1000)
        val old = UIMessage.user("earlier details ".repeat(100))
        val summary = ContextSummary(
            conversationId = "c",
            text = "Earlier task constraints.",
            sources = contextUnits(listOf(old)).map { it.source },
            tokensBefore = 500,
            tokensAfter = 8,
        )
        store.summaries.add(summary)
        val messages = listOf(
            old,
            UIMessage.assistant("").copy(parts = List(2) { index ->
                UIMessagePart.Tool("call-$index", "read", "{}", listOf(UIMessagePart.Text("x".repeat(1500))))
            }),
            UIMessage.user("recent one"),
            UIMessage.assistant("details ".repeat(830)),
            UIMessage.user("recent two"),
        )
        repeat(2) {
            val result = manager(messages, config = config, tools = recall).prepare(messages, build = ::build)
            assertEquals(listOf(summary), store.summaries)
            val tools = result.messages.flatMap { it.getTools() }
            assertTrue(tools.first().output.toString().contains("history_read"))
            assertEquals(messages[1].getTools().last(), tools.last())
        }
    }

    @Test
    fun `small pruning batches still wait for the minimum when there is no context pressure`() = runTest {
        val config = pruneSettings.copy(pruneMinimumTokens = 1000)
        val messages = listOf(
            UIMessage.user("old task"),
            UIMessage.assistant("").copy(parts = List(2) { index ->
                UIMessagePart.Tool("call-$index", "read", "{}", listOf(UIMessagePart.Text("x".repeat(1500))))
            }),
            UIMessage.user("recent one"),
            UIMessage.assistant("done"),
            UIMessage.user("recent two"),
        )
        val result = manager(messages, config = config, tools = recall).prepare(messages, build = ::build)
        assertTrue(store.summaries.isEmpty())
        assertEquals(messages[1].getTools(), result.messages.flatMap { it.getTools() })
    }

    @Test
    fun `summary remains the fallback when a small pruning batch is insufficient`() = runTest {
        val config = pruneSettings.copy(pruneMinimumTokens = 1000)
        val messages = listOf(
            UIMessage.user("old task"),
            UIMessage.assistant("").copy(parts = List(2) { index ->
                UIMessagePart.Tool(
                    "call-$index",
                    "read",
                    "{}",
                    listOf(UIMessagePart.Text("ORIGINAL_$index " + "x".repeat(1500)))
                )
            }),
            UIMessage.user("recent one"),
            UIMessage.assistant("details ".repeat(1200)),
            UIMessage.user("recent two"),
        )
        val requests = mutableListOf<List<UIMessage>>()
        val transcripts = mutableListOf<String>()
        manager(messages, config = config, tools = recall, summarize = { text, _ ->
            assertTrue(requests.any { request ->
                request.flatMap { it.getTools() }.any {
                    it.output.toString().contains(
                        "Old tool result content cleared"
                    )
                }
            })
            transcripts.add(text)
            "Original task constraints and observations."
        }).prepare(messages, build = {
            requests.add(it)
            build(it)
        })
        assertTrue(store.summaries.isNotEmpty())
        assertTrue(transcripts.any { it.contains("ORIGINAL_0") })
        assertTrue(transcripts.none { it.contains("Old tool result content cleared") })
        assertTrue(store.summaries.all { contextSourcesMatch(messages, it.sources) })
    }

    @Test
    fun `manual summary uses original observations instead of prune placeholders`() = runTest {
        val messages = toolHistory()
        val transcripts = mutableListOf<String>()
        manager(messages, config = pruneSettings, tools = recall, summarize = { text, _ ->
            transcripts.add(text)
            "Original task constraints and observations."
        }).prepare(messages, build = ::build, force = true, keepRecentMessages = 3)
        assertTrue(transcripts.any { it.contains("SECRET_0") })
        assertTrue(transcripts.none { it.contains("Old tool result content cleared") })
        assertTrue(store.summaries.all { contextSourcesMatch(messages, it.sources) })
    }

    @Test
    fun `summary still runs when pruning cannot bring the full request within budget`() = runTest {
        val messages = listOf(UIMessage.user("large user constraints ".repeat(500))) + toolHistory()
        manager(messages, config = pruneSettings, tools = recall).prepare(messages, build = ::build)
        assertTrue(store.summaries.isNotEmpty())
        assertTrue(store.summaries.all { contextSourcesMatch(messages, it.sources) })
    }

    @Test
    fun `successful step boundaries unlock only consumed tools and keep recall canonical`() = runTest {
        fun tool(index: Int) = UIMessagePart.Tool(
            "call-$index",
            "read",
            "{}",
            listOf(UIMessagePart.Text("original_$index " + "x".repeat(900)))
        )
        val current = UIMessage.assistant("").copy(parts = List(3, ::tool))
        val messages = listOf(UIMessage.user("work"), current)
        val history = ContextHistory(messages)
        val managed =
            manager(messages, config = pruneSettings.copy(autoCompress = false), tools = recall, history = history)
        val first = managed.prepare(messages, build = ::build)
        assertEquals(current.getTools(), first.messages.flatMap { it.getTools() })
        val generated = current.copy(parts = current.parts + listOf(tool(3), tool(4)))
        managed.recordUsage(generated, newPartsFrom = current.parts.size)
        val next = listOf(messages.first(), generated)
        val result = managed.prepare(next, build = ::build)
        val projected = result.messages.flatMap { it.getTools() }
        assertTrue(projected.first().output.toString().contains("Old tool result content cleared"))
        assertEquals(generated.getTools().takeLast(3), projected.takeLast(3))
        assertEquals(next, history.messages)
        assertTrue(history.messages.last().getTools().first().output.toString().contains("original_0"))
    }

    @Test
    fun `automatic compaction keeps the newest user message and all original sources`() = runTest {
        val messages = List(10) { UIMessage.user("old details ".repeat(160)) } + UIMessage.user("continue")
        val original = messages.toList()
        val result = manager(messages).prepare(messages, build = ::build)
        assertTrue(store.summaries.isNotEmpty())
        assertEquals(messages.last(), result.messages.last())
        assertEquals(original, messages)
        assertTrue(store.summaries.all { contextSourcesMatch(messages, it.sources) })
        assertTrue(result.messages.sumOf(::estimateContextMessage) < 3200)
    }

    @Test
    fun `one enormous completed tool result can be summarized without losing its original`() = runTest {
        val output = "tool detail ".repeat(2000)
        val messages = listOf(UIMessage.user("inspect"), UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "read", "{}", listOf(UIMessagePart.Text(output))),
        )))
        manager(messages).prepare(messages, build = ::build)
        assertTrue(
            store.summaries.any { summary -> summary.sources.any { it.messageId == messages.last().id.toString() } }
        )
        assertEquals(output, (messages.last().getTools().single().output.single() as UIMessagePart.Text).text)
    }

    @Test
    fun `an image earlier in a fresh tool batch survives compaction until the model sees it`() = runTest {
        val image = UIMessagePart.Image("file:///image.png")
        val current = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("image", "inspect", "{}", listOf(image)),
            UIMessagePart.Tool("text", "status", "{}", listOf(UIMessagePart.Text("ready"))),
        ))
        val messages = List(5) { UIMessage.user("details ".repeat(150)) } + UIMessage.user("inspect") + current
        val result = manager(messages).prepare(messages, build = ::build)
        assertTrue(store.summaries.isNotEmpty())
        assertTrue(store.summaries.none { summary -> summary.sources.any { it.messageId == current.id.toString() } })
        assertTrue(result.messages.flatMap { it.getTools() }.flatMap { it.output }.contains(image))
    }

    @Test
    fun `disabled automatic compaction does not call the summary model`() = runTest {
        val messages = listOf(UIMessage.user("short request"))
        var calls = 0
        val result = manager(messages, auto = false, summarize = { _, _ ->
            calls++
            "summary"
        })
            .prepare(messages, build = ::build)
        assertEquals(messages, result.messages)
        assertEquals(0, calls)
    }

    @Test
    fun `failed summarization never commits a replacement`() = runTest {
        val messages = List(8) { UIMessage.user("details ".repeat(200)) }
        val result = runCatching {
            manager(messages, summarize = { _, _ -> throw IOException("offline") }).prepare(messages, build = ::build)
        }
        assertTrue(result.isFailure)
        assertTrue(store.summaries.isEmpty())
    }

    @Test
    fun `a stale source commit is rejected instead of applying its summary`() = runTest {
        val messages = List(8) { UIMessage.user("details ".repeat(200)) }
        val result = runCatching { manager(messages, persist = { false }).prepare(messages, build = ::build) }
        assertTrue(result.isFailure)
        assertTrue(store.summaries.isEmpty())
    }

    @Test
    fun `only explicit size errors qualify for context recovery`() {
        assertTrue(isContextOverflow(IllegalStateException("context_length_exceeded")))
        assertTrue(isContextOverflow(IOException("wrapper", IllegalArgumentException("prompt is too long"))))
        assertFalse(isContextOverflow(IOException("connection reset")))
        assertFalse(isContextOverflow(IllegalStateException("401 unauthorized")))
    }
}
