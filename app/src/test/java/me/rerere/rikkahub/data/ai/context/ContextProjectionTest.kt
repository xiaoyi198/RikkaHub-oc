package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.tools.contextTextWithinBudget
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextProjectionTest {
    @Test
    fun `summaries project history without modifying the transcript`() {
        val original = listOf(UIMessage.user("old"), UIMessage.assistant("answer"), UIMessage.user("new"))
        val units = contextUnits(original)
        val summary = ContextSummary(
            conversationId = "conversation",
            text = "old context",
            sources = units.take(2).map { it.source },
            tokensBefore = 200,
            tokensAfter = 20,
        )
        val result = projectContext(units, listOf(summary))
        assertEquals(2, result.size)
        assertEquals(summary, result.first().summary)
        assertEquals(original.last(), result.last().message)
        assertEquals(listOf("old", "answer", "new"), original.map { it.toText() })
        assertFalse(original.any { it.isContextCheckpoint })
        assertEquals(result.first().message.id, projectContext(units, listOf(summary)).first().message.id)
        assertEquals(original[1].createdAt, result.first().message.createdAt)
        assertEquals(result.first().message, projectContext(units, listOf(summary)).first().message)
    }

    @Test
    fun `editing or switching a source branch invalidates its summary`() {
        val original = UIMessage.user("constraint A")
        val sources = contextUnits(listOf(original)).map { it.source }
        val summary =
            ContextSummary(conversationId = "c", text = "A", sources = sources, tokensBefore = 100, tokensAfter = 1)
        val edited = original.copy(parts = listOf(UIMessagePart.Text("constraint B")))
        assertFalse(contextSourcesMatch(listOf(edited), sources))
        assertFalse(contextSourcesMatch(listOf(UIMessage.user("constraint A")), sources))
        assertEquals(null, projectContext(contextUnits(listOf(edited)), listOf(summary)).single().summary)
    }

    @Test
    fun `a source range is not valid if another message was inserted inside it`() {
        val original = listOf(UIMessage.user("one"), UIMessage.assistant("two"))
        val sources = contextUnits(original).map { it.source }
        assertFalse(contextSourcesMatch(listOf(original[0], UIMessage.user("inserted"), original[1]), sources))
    }

    @Test
    fun `completed tool ranges remain valid when the same assistant message gains more steps`() {
        val first = UIMessagePart.Tool("call-1", "read", "{}", listOf(UIMessagePart.Text("result 1")))
        val second = UIMessagePart.Tool("call-2", "write", "{}", listOf(UIMessagePart.Text("result 2")))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(UIMessagePart.Text("plan"), first, second))
        val units = contextUnits(listOf(message))
        assertEquals(2, units.size)
        assertEquals(listOf(UIMessagePart.Text("plan"), first), units[0].message.parts)
        assertEquals(listOf(second), units[1].message.parts)
        val later = message.copy(parts = message.parts + UIMessagePart.Text("done"))
        assertTrue(contextSourcesMatch(listOf(later), units.map { it.source }))
        assertTrue(units.all { it.message.getTools().all { tool -> tool.isExecuted } })
    }

    @Test
    fun `transcripts include full old tool outputs and document text`() {
        val toolText = "a".repeat(12_000) + "IMPORTANT_VALUE"
        val message = UIMessage.assistant("").copy(parts = listOf(
            UIMessagePart.Tool("call", "shell", "command", listOf(UIMessagePart.Text(toolText))),
            UIMessagePart.Document("file:///doc.txt", "doc.txt"),
        ))
        val transcript = contextTranscript(message) { "EXTRACTED_DOCUMENT" }
        assertTrue(transcript.contains("IMPORTANT_VALUE"))
        assertTrue(transcript.contains("EXTRACTED_DOCUMENT"))
        assertEquals(transcript, contextTranscript(message) { "EXTRACTED_DOCUMENT" })
    }

    @Test
    fun `small windows clamp the tail and summary while honoring output reservation`() {
        val settings = ContextManagementSettings(recentTokens = 20_000, summaryTokens = 4096)
        val budget = contextBudget(4096, 2048, settings)
        assertTrue(budget.input < 2048)
        assertTrue(budget.trigger <= budget.input)
        assertTrue(budget.target < budget.trigger)
        assertTrue(budget.target <= budget.trigger - 4096 / 20)
        assertTrue(budget.chunk <= budget.input)
        assertTrue(budget.recent <= budget.input / 2)
        assertTrue(budget.summary <= budget.input / 4)
        assertFalse(settings.enabled)
    }

    @Test
    fun `pagination stays within the mixed language budget and preserves unicode boundaries`() {
        val text = "中文🙂hello ".repeat(200)
        var offset = 0
        val pages = mutableListOf<String>()
        while (offset < text.length) {
            val page = contextTextWithinBudget(text.substring(offset), 64)
            assertTrue(page.isNotEmpty())
            assertTrue(estimateContextText(page) <= 64)
            assertFalse(page.last().isHighSurrogate())
            pages.add(page)
            offset += page.length
        }
        assertEquals(text, pages.joinToString(""))
    }
}
