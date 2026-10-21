package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.context.ContextHistory
import me.rerere.rikkahub.data.ai.context.contextFingerprint
import me.rerere.rikkahub.data.ai.context.contextSourcesMatch
import me.rerere.rikkahub.data.ai.context.contextTranscript
import me.rerere.rikkahub.data.ai.context.estimateContextText
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.repository.ContextSummaryRepository

/** These closures only expose the current run's selected branch, including its in-memory updates. */
fun createContextHistoryTools(
    conversationId: String,
    history: ContextHistory,
    repository: ContextSummaryRepository,
    maxRecallTokens: Int,
    allowImages: Boolean = false,
): List<Tool> = listOf(
    Tool(
        name = "history_search",
        description = """
            Search the current conversation's selected branch, including old tool calls and results.
            Use this when a history summary omits an exact fact, command, error, decision or value.
            Returns source message IDs and summary IDs; use history_read to recover original details.
            Other conversations and alternative branches are never searched.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("query", historyProperty("string", "Focused search keywords"))
                    put("limit", historyProperty("integer", "Result count, 1–10, default 5"))
                },
                required = listOf("query"),
            )
        },
        execute = { input ->
            val query = input.jsonObject["query"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            require(query.isNotEmpty() && query.length <= 512) { "query must contain 1–512 characters" }
            val limit = (input.jsonObject["limit"]?.jsonPrimitive?.intOrNull ?: 5).coerceIn(1, 10)
            val messages = history.messages
            val byId = messages.associateBy { it.id.toString() }
            val matches = repository.search(conversationId, query, limit * 2).filter { match ->
                byId[match.messageId]?.let { contextFingerprint(it) == match.fingerprint } == true
            }.take(limit)
            val summaries = repository.getAll(conversationId).filter { summary ->
                summary.text.contains(query, ignoreCase = true) && contextSourcesMatch(messages, summary.sources)
            }.takeLast(limit)
            var remaining = (maxRecallTokens - 256).coerceAtLeast(64)
            val result = buildJsonObject {
                put("scope", "current conversation, selected branch")
                put("matches", buildJsonArray {
                    matches.forEach { match ->
                        if (remaining < 48) return@forEach
                        val snippet = contextTextWithinBudget(match.snippet, minOf(remaining - 32, 256))
                        remaining -= estimateContextText(snippet) + 32
                        add(buildJsonObject {
                            put("id", match.messageId)
                            put("type", "message")
                            put("snippet", snippet)
                        })
                    }
                    summaries.forEach { summary ->
                        if (remaining < 48) return@forEach
                        val snippet = contextTextWithinBudget(summary.text, minOf(remaining - 32, 256))
                        remaining -= estimateContextText(snippet) + 32
                        add(buildJsonObject {
                            put("id", summary.id)
                            put("type", "summary")
                            put("snippet", snippet)
                        })
                    }
                })
                put("hint", "Use history_read(id, offset) to read the original text. No matches does not mean the fact never existed; try other keywords.")
            }
            listOf(UIMessagePart.Text(result.toString()))
        },
    ),
    Tool(
        name = "history_read",
        description = """
            Read an original message, or expand a summary into its original source messages and tool results.
            Accepts IDs from history_search or [Conversation history summary: ...].
            Only the current selected branch is accessible. Results are paginated by character offset;
            follow next_offset when truncated. Documents include extracted text. To view an original
            image, set include_image=true and image_offset (default 0); one image is returned at a time.
        """.trimIndent(),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("id", historyProperty("string", "Message or summary ID"))
                    put("offset", historyProperty("integer", "Character offset, default 0"))
                    put("max_tokens", historyProperty("integer", "Optional smaller return budget"))
                    put("include_image", historyProperty("boolean"))
                    put("image_offset", historyProperty("integer"))
                },
                required = listOf("id"),
            )
        },
        execute = { input ->
            val args = input.jsonObject
            val id = args["id"]?.jsonPrimitive?.contentOrNull ?: error("id is required")
            val offset = (args["offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val budget = (args["max_tokens"]?.jsonPrimitive?.intOrNull ?: maxRecallTokens).coerceIn(64, maxRecallTokens)
            val messages = history.messages
            val message = messages.firstOrNull { it.id.toString() == id }
            val originals = if (message != null) listOf(message) else {
                val summary = repository.getAll(conversationId).firstOrNull { it.id == id }
                    ?.takeIf { contextSourcesMatch(messages, it.sources) }
                    ?: error("Source not found on the current branch. Search again.")
                val byId = messages.associateBy { it.id.toString() }
                summary.sources.map { source ->
                    val original = byId.getValue(source.messageId)
                    original.copy(parts = original.parts.subList(source.fromPart, source.toPart))
                }
            }
            val text = originals.joinToString("\n\n") {
                contextTranscript(it, DocumentAsPromptTransformer::readDocumentContent)
            }
            val images = originals.flatMap { original ->
                original.parts.flatMap { part -> if (part is UIMessagePart.Tool) part.output else listOf(part) }
            }.filterIsInstance<UIMessagePart.Image>()
            val includeImage = args["include_image"]?.jsonPrimitive?.contentOrNull == "true"
            val imageOffset = (args["image_offset"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0)
            val start = offset.coerceAtMost(text.length)
            val page = contextTextWithinBudget(text.substring(start), (budget - 128).coerceAtLeast(32))
            val end = start + page.length
            val result = buildJsonObject {
                put("id", id)
                put("offset", start)
                put("total_characters", text.length)
                put("text", page)
                put("image_count", images.size)
                if (includeImage && !allowImages) put("image_note", "The current model does not support image input.")
                put("truncated", end < text.length)
                if (end < text.length) put("next_offset", end)
            }
            listOf(UIMessagePart.Text(result.toString())) +
                if (includeImage && allowImages) listOfNotNull(images.getOrNull(imageOffset)) else emptyList()
        },
    ),
)

private fun historyProperty(type: String, description: String? = null) = buildJsonObject {
    put("type", type)
    if (description != null) put("description", description)
}

fun contextTextWithinBudget(text: String, tokens: Int): String {
    if (estimateContextText(text) <= tokens) return text
    var low = 0
    var high = text.length
    while (low < high) {
        val middle = low + (high - low + 1) / 2
        if (estimateContextText(text.substring(0, middle)) <= tokens) low = middle else high = middle - 1
    }
    // Do not split a supplementary Unicode character across pages.
    if (low > 0 && low < text.length && text[low - 1].isHighSurrogate() && text[low].isLowSurrogate()) low--
    return text.substring(0, low)
}
