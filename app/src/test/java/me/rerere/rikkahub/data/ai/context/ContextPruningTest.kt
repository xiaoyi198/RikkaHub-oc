package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.contextHistoryToolSource
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextSummary
import me.rerere.rikkahub.utils.JsonInstant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextPruningTest {
    private val settings = ContextManagementSettings(
        enabled = true,
        prune = true,
        pruneProtectTokens = 1,
        pruneMinimumTokens = 1,
    )

    private fun tool(id: String, name: String = "workspace_shell", text: String = "observation ".repeat(300)) =
        UIMessagePart.Tool(id, name, "{\"command\":\"inspect\"}", listOf(UIMessagePart.Text(text)))

    private fun assistant(vararg parts: UIMessagePart) = UIMessage.assistant("").copy(parts = parts.toList())

    private fun history() = listOf(
        UIMessage.user("old task"),
        assistant(tool("a"), tool("b"), tool("c")),
        UIMessage.user("recent one"),
        UIMessage.assistant("answer"),
        UIMessage.user("recent two"),
    )

    private fun project(messages: List<UIMessage>) = projectContext(contextUnits(messages), emptyList())

    @Test
    fun `prune preserves calls and raw outputs while replacing only old request results`() {
        val messages = history()
        val raw = messages[1].getTools()
        val result = pruneContext(project(messages), messages, settings, 10_000, true)
        val tools = result.items.flatMap { it.message.getTools() }
        assertEquals(2, result.toolCount)
        assertTrue(result.tokensSaved > 1000)
        assertEquals(raw[0].copy(output = tools[0].output), tools[0])
        assertTrue(
            (tools[0].output.single() as UIMessagePart.Text).text.contains(
                "history_read(id=\"${messages[1].id}\", tool_call_id=\"a\")"
            )
        )
        assertEquals(raw[2], tools[2])
        assertEquals(raw, messages[1].getTools())
        assertTrue(contextSourcesMatch(messages, result.sources.toList()))
    }

    @Test
    fun `switches and unavailable recall leave the projection intact`() {
        val messages = history()
        val items = project(messages)
        assertEquals(items, pruneContext(items, messages, settings.copy(prune = false), 10_000, true).items)
        assertEquals(items, pruneContext(items, messages, settings.copy(enabled = false), 10_000, true).items)
        assertEquals(items, pruneContext(items, messages, settings, 10_000, false).items)
    }

    @Test
    fun `latest two user turns remain complete regardless of output size`() {
        val messages = history() + assistant(tool("recent", text = "new ".repeat(5000)))
        val result = pruneContext(project(messages), messages, settings, 10_000, true)
        assertEquals(2, result.toolCount)
        assertEquals(messages.last().getTools().single(), result.items.last().message.getTools().single())
    }

    @Test
    fun `long tool runs prune consumed results but preserve every fresh result in the batch`() {
        val current = assistant(tool("a"), tool("b"), tool("c"), tool("fresh-1"), tool("fresh-2"))
        val messages = listOf(UIMessage.user("continue working"), current)
        assertEquals(0, pruneContext(project(messages), messages, settings, 10_000, true).toolCount)
        val result = pruneContext(project(messages), messages, settings, 10_000, true, current.id.toString(), 3)
        assertEquals(2, result.toolCount)
        assertEquals(current.getTools().takeLast(3), result.items.flatMap { it.message.getTools() }.takeLast(3))
    }

    @Test
    fun `minimum savings count the placeholder overhead and apply as one batch`() {
        val messages = history()
        val items = project(messages)
        val result = pruneContext(items, messages, settings, 10_000, true)
        val belowMinimum = settings.copy(pruneMinimumTokens = result.tokensSaved + 1)
        assertEquals(items, pruneContext(items, messages, belowMinimum, 10_000, true).items)
        assertEquals(
            result.items,
            pruneContext(items, messages, settings.copy(pruneMinimumTokens = result.tokensSaved), 10_000, true).items
        )
        val tiny = messages.map { message ->
            message.copy(
                parts = message.parts.map {
                    if (it is UIMessagePart.Tool) {
                        it.copy(
                            output = listOf(UIMessagePart.Text("ok"))
                        )
                    } else {
                        it
                    }
                }
            )
        }
        assertEquals(0, pruneContext(project(tiny), tiny, settings, 10_000, true).toolCount)
    }

    @Test
    fun `skills user decisions custom tools and nontext outputs are protected`() {
        val protected = listOf(
            tool("skill", "skill"), tool("use", "use_skill"), tool("ask", "ask_user"), tool("custom", "important"),
            tool("pending").copy(output = emptyList(), approvalState = ToolApprovalState.Pending),
            tool("denied").copy(approvalState = ToolApprovalState.Denied("no")),
            tool("answered").copy(approvalState = ToolApprovalState.Answered("keep it")),
            tool(
                "image"
            ).copy(output = listOf(UIMessagePart.Text("caption"), UIMessagePart.Image("file:///image.png"))),
            tool("document").copy(output = listOf(UIMessagePart.Document("file:///doc.txt", "doc.txt"))),
        )
        val messages =
            listOf(UIMessage.user("old"), assistant(tool("a"), *protected.toTypedArray(), tool("b"))) +
                history().takeLast(3)
        val result =
            pruneContext(
                project(messages),
                messages,
                settings.copy(pruneProtectedTools = listOf("important")),
                10_000,
                true
            )
        assertEquals(1, result.toolCount)
        val tools = result.items.flatMap { it.message.getTools() }.associateBy { it.toolCallId }
        protected.forEach { assertEquals(it, tools[it.toolCallId]) }
    }

    @Test
    fun `pruning stops at the newest summary boundary`() {
        val messages = history()
        val items = project(messages)
        val summary =
            ContextSummary(
                conversationId = "c",
                text = "boundary",
                sources = emptyList(),
                tokensBefore = 100,
                tokensAfter = 10
            )
        val boundary = ContextItem(UIMessage.user("boundary"), emptyList(), summary)
        val separated = items.take(3) + boundary + items.drop(3)
        assertEquals(separated, pruneContext(separated, messages, settings, 10_000, true).items)
    }

    @Test
    fun `protection budgets adapt to small windows and support large windows without overflow`() {
        val defaults = ContextManagementSettings()
        assertEquals(PruneBudget(40_000, 20_000), contextPruneBudget(120_000, defaults))
        assertEquals(PruneBudget(1200, 600), contextPruneBudget(3000, defaults))
        assertEquals(
            PruneBudget(1500, 1500),
            contextPruneBudget(3000, settings.copy(pruneProtectTokens = 40_000, pruneMinimumTokens = 20_000))
        )
        assertEquals(PruneBudget(40_000, 20_000), contextPruneBudget(Int.MAX_VALUE, defaults))
    }

    @Test
    fun `pruning is deterministic across runs and invalidates edited sources`() {
        val messages = history()
        val result = pruneContext(project(messages), messages, settings, 10_000, true)
        assertEquals(result, pruneContext(project(messages), messages, settings, 10_000, true))
        val continued = messages + UIMessage.assistant("done") + UIMessage.user("next")
        val later = pruneContext(project(continued), continued, settings, 10_000, true)
        assertEquals(result.items.take(3), later.items.take(3))
        val edited = messages.toMutableList().apply {
            this[1] = messages[1].copy(parts = listOf(tool("a", text = "changed ".repeat(500)), tool("b"), tool("c")))
        }
        assertFalse(contextSourcesMatch(edited, result.sources.toList()))
        val updated = pruneContext(project(edited), edited, settings, 10_000, true)
        assertTrue(contextSourcesMatch(edited, updated.sources.toList()))
        val branch = edited.toMutableList().apply { this[1] = assistant(tool("a"), tool("b"), tool("c")) }
        val switched = pruneContext(project(branch), branch, settings, 10_000, true)
        assertTrue(switched.sources.none { it.messageId == messages[1].id.toString() })
    }

    @Test
    fun `targeted recall recovers the complete original call without unrelated output`() {
        val message = assistant(tool("a", text = "full original A"), tool("b", text = "unrelated B"))
        val source = contextHistoryToolSource(listOf(message), "a").single()
        assertEquals(message.id, source.id)
        assertEquals(listOf(message.getTools().first()), source.parts)
        assertTrue(contextTranscript(source).contains("full original A"))
        assertFalse(contextTranscript(source).contains("unrelated B"))
        assertTrue(runCatching { contextHistoryToolSource(listOf(message), "absent") }.isFailure)
    }

    @Test
    fun `old settings deserialize with pruning disabled and new values normalize`() {
        assertFalse(JsonInstant.decodeFromString<ContextManagementSettings>("{\"enabled\":true}").prune)
        val normalized = settings.copy(
            pruneProtectTokens = -1,
            pruneMinimumTokens = Int.MAX_VALUE,
            pruneProtectedTools = listOf(" read ", "read", "")
        ).normalized()
        assertEquals(0, normalized.pruneProtectTokens)
        assertEquals(1_000_000, normalized.pruneMinimumTokens)
        assertEquals(listOf("read"), normalized.pruneProtectedTools)
        assertEquals(
            normalized,
            JsonInstant.decodeFromString<ContextManagementSettings>(JsonInstant.encodeToString(normalized))
        )
    }
}
