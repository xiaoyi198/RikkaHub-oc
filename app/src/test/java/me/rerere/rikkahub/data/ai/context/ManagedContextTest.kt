package me.rerere.rikkahub.data.ai.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import me.rerere.ai.agent.StepRequest
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.TextGenerationParams
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ContextManagementSettings
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
        override suspend fun getAll(conversationId: String) = summaries.toList()
        override suspend fun index(conversationId: String, messages: List<UIMessage>) = Unit
    }

    private fun manager(
        messages: List<UIMessage>,
        auto: Boolean = true,
        persist: suspend (ContextSummary) -> Boolean = { store.summaries.add(it); true },
        summarize: suspend (String, Int) -> String = { _, _ -> "User constraints and completed findings." },
    ) = ManagedContext(
        conversationId = "c",
        settings = ContextManagementSettings(enabled = true, autoCompress = auto, recentTokens = 400),
        window = model.contextLength,
        maxOutput = 400,
        tools = emptyList(),
        history = ContextHistory(messages),
        repository = store,
        status = MutableStateFlow(null),
        persist = persist,
        summarize = summarize,
    )

    private fun build(messages: List<UIMessage>) = StepRequest(messages, TextGenerationParams(model))

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
        assertTrue(store.summaries.any { summary -> summary.sources.any { it.messageId == messages.last().id.toString() } })
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
        val result = manager(messages, auto = false, summarize = { _, _ -> calls++; "summary" })
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
