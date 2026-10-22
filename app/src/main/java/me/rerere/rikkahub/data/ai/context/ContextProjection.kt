package me.rerere.rikkahub.data.ai.context

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.data.model.ContextSource
import me.rerere.rikkahub.data.model.ContextSummary
import me.rerere.rikkahub.utils.JsonInstant
import java.security.MessageDigest
import kotlin.math.ceil

data class ContextUnit(val message: UIMessage, val source: ContextSource)

/** A completed Tool part contains both its invocation and result: they are always kept together. */
fun contextUnits(messages: List<UIMessage>): List<ContextUnit> = buildList {
    messages.forEach { message ->
        var start = 0
        message.parts.forEachIndexed { index, part ->
            if (message.role == MessageRole.ASSISTANT && part is UIMessagePart.Tool && part.isExecuted) {
                add(contextUnit(message, start, index + 1))
                start = index + 1
            }
        }
        if (start < message.parts.size) add(contextUnit(message, start, message.parts.size))
    }
}

private fun contextUnit(message: UIMessage, from: Int, to: Int): ContextUnit {
    val projected = message.copy(parts = message.parts.subList(from, to))
    return ContextUnit(
        message = projected,
        source = ContextSource(message.id.toString(), from, to, contextFingerprint(projected)),
    )
}

fun contextFingerprint(message: UIMessage): String = contextHash(
    "${message.role}:${message.isContextCheckpoint}:${JsonInstant.encodeToString(message.parts)}"
)

internal fun contextHash(text: String): String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** Also validates partial sources inside an assistant message which has since accumulated more steps. */
fun contextSourcesMatch(messages: List<UIMessage>, sources: List<ContextSource>): Boolean {
    if (sources.isEmpty()) return false
    val byId = messages.associateBy { it.id.toString() }
    val indices = messages.mapIndexed { index, message -> message.id.toString() to index }.toMap()
    return sources.all { source ->
        val message = byId[source.messageId] ?: return@all false
        source.fromPart >= 0 && source.toPart > source.fromPart && source.toPart <= message.parts.size &&
            contextFingerprint(message.copy(parts = message.parts.subList(source.fromPart, source.toPart))) ==
            source.fingerprint
    } && sources.zipWithNext().all { (left, right) ->
        val leftIndex = indices.getValue(left.messageId)
        val rightIndex = indices.getValue(right.messageId)
        if (leftIndex == rightIndex) {
            left.toPart == right.fromPart
        } else {
            rightIndex == leftIndex + 1 &&
                left.toPart == messages[leftIndex].parts.size && right.fromPart == 0
        }
    }
}

data class ContextItem(
    val message: UIMessage,
    val sources: List<ContextSource>,
    val summary: ContextSummary? = null,
)

/** Select only summaries whose exact source sequence occurs on the selected branch. */
fun projectContext(
    units: List<ContextUnit>,
    summaries: List<ContextSummary>,
    recallAvailable: Boolean = true,
): List<ContextItem> = buildList {
    var index = 0
    while (index < units.size) {
        val summary = summaries.asSequence()
            .filter { it.sources.firstOrNull() == units[index].source }
            .filter { candidate ->
                index + candidate.sources.size <= units.size &&
                    candidate.sources.indices.all { units[index + it].source == candidate.sources[it] }
            }
            .maxWithOrNull(compareBy<ContextSummary> { it.sources.size }.thenBy { it.createdAt })
        if (summary == null) {
            add(ContextItem(units[index].message, listOf(units[index].source)))
            index++
        } else {
            add(ContextItem(
                message = UIMessage.user(
                    "[Conversation history summary: ${summary.id}]\n${summary.text}\n" +
                        "[Sources: ${summary.sources.first().messageId} … ${summary.sources.last().messageId}." +
                        (if (recallAvailable) {
                            " Use history_read with this summary id to recover omitted details."
                        } else {
                            ""
                        }) +
                        "]"
                ).copy(
                    id = kotlin.uuid.Uuid.parse(summary.id),
                    createdAt = units[index + summary.sources.size - 1].message.createdAt,
                    isSynthetic = true,
                ),
                sources = summary.sources,
                summary = summary,
            ))
            index += summary.sources.size
        }
    }
}

/** Conservative fallback for mixed-language text; media is accounted separately, not as URL/base64 text. */
fun estimateContextText(text: String): Int {
    var ascii = 0
    var unicode = 0
    text.forEach { if (it.code < 128) ascii++ else unicode++ }
    return ceil(ascii / 3.0 + unicode * 1.5).toInt()
}

fun estimateContextMessage(message: UIMessage): Int = 12 + message.parts.sumOf { part ->
    when (part) {
        is UIMessagePart.Text -> estimateContextText(part.text)
        is UIMessagePart.Reasoning -> estimateContextText(part.reasoning)
        is UIMessagePart.Tool -> estimateContextText(part.toolName + part.input) +
            part.output.sumOf { estimateContextMessage(UIMessage.user("").copy(parts = listOf(it))) } + 16
        is UIMessagePart.Image -> 2048
        is UIMessagePart.Video -> 8192
        is UIMessagePart.Audio -> 4096
        is UIMessagePart.Document -> 4096
        else -> estimateContextText(JsonInstant.encodeToString(part))
    }
}

/** Plain transcript for summaries/search. Original structured parts always remain in the conversation. */
fun contextTranscript(
    message: UIMessage,
    readDocument: (UIMessagePart.Document) -> String? = { null },
): String = buildString {
    appendLine("[${message.role}: ${message.id}]")
    message.parts.forEach { part ->
        when (part) {
            is UIMessagePart.Text -> appendLine(part.text)
            is UIMessagePart.Tool -> {
                appendLine("[Tool call ${part.toolCallId}: ${part.toolName}] ${part.input}")
                part.output.forEach {
                    appendLine(contextTranscript(message.copy(parts = listOf(it)), readDocument))
                }
            }
            is UIMessagePart.Reasoning -> Unit
            is UIMessagePart.Image -> appendLine(
                "[Image: ${part.url.takeUnless { it.startsWith("data:") } ?: "inline image"}]"
            )
            is UIMessagePart.Document -> {
                appendLine("[Document: ${part.fileName}, ${part.url}]")
                readDocument(part)?.let { appendLine(it) }
            }
            is UIMessagePart.Video -> appendLine("[Video: ${part.url.take(256)}]")
            is UIMessagePart.Audio -> appendLine("[Audio: ${part.url.take(256)}]")
            else -> appendLine(JsonInstant.encodeToString(part))
        }
    }
}

data class ContextBudget(
    val input: Int,
    val trigger: Int,
    val target: Int,
    val chunk: Int,
    val recent: Int,
    val summary: Int,
    val output: Int,
)

fun contextBudget(window: Int, maxOutput: Int?, settings: ContextManagementSettings): ContextBudget {
    require(window > 0) { "请先配置模型的上下文容量，再使用增强上下文管理。" }
    val output = maxOutput?.coerceAtLeast(1) ?: (window / 10).coerceIn(256, 8192)
        .coerceAtMost((window / 2).coerceAtLeast(1))
    require(output < window) { "回复长度上限必须小于模型上下文容量。" }
    val safety = (window / 20).coerceAtLeast(128).coerceAtMost((window / 4).coerceAtLeast(1))
    val input = (window - output - safety).coerceAtLeast(1)
    val trigger = minOf(input, (window.toLong() * settings.triggerPercent / 100).toInt()).coerceAtLeast(1)
    val targetPercent = settings.targetPercent.coerceIn(10, settings.triggerPercent.coerceIn(50, 95) - 5)
    val target = minOf(
        (trigger - (window / 20).coerceAtLeast(1)).coerceAtLeast(1),
        (window.toLong() * targetPercent / 100).toInt()
    )
        .coerceAtLeast(1)
    val chunk = settings.compressionChunkTokens.takeIf { it > 0 }
        ?.coerceIn(minOf(256, input), input)
        ?: (input / 3).coerceIn(minOf(256, input), input)
    val recent = (settings.recentTokens.takeIf { it > 0 } ?: (input / 5))
        .coerceIn(1, (input / 2).coerceAtLeast(1))
    val summary = (settings.summaryTokens.takeIf { it > 0 } ?: (input / 12))
        .coerceIn(1, (input / 4).coerceAtLeast(1))
    return ContextBudget(input, trigger, target, chunk, recent, summary, output)
}
