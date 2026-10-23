package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.context.ContextInspection
import me.rerere.rikkahub.data.ai.context.contextBudget
import me.rerere.rikkahub.data.ai.context.contextUnits
import me.rerere.rikkahub.data.ai.context.isPrunableTool
import me.rerere.rikkahub.data.ai.context.projectContext
import me.rerere.rikkahub.data.model.Assistant
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ContextSummaryRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupScope
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.utils.formatNumber
import org.koin.compose.koinInject
import java.text.NumberFormat
import java.text.DateFormat
import java.util.Date

@Composable
internal fun ContextInsightsSheet(
    conversation: Conversation,
    assistant: Assistant,
    model: Model?,
    inspection: ContextInspection?,
    onDismiss: () -> Unit,
    onSettings: () -> Unit,
) {
    val settings = LocalSettings.current.contextManagement
    val repository = koinInject<ContextSummaryRepository>()
    val summaryFlow = remember(conversation.id) { repository.observe(conversation.id.toString()) }
    val batchFlow = remember(conversation.id) { repository.observePruneBatches(conversation.id.toString()) }
    val summaries by summaryFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val batches by batchFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val messages = conversation.currentMessages
    val messageIds = messages.map { it.id }
    val recall = settings.enabled && settings.historyTools && model?.abilities?.contains(ModelAbility.TOOL) == true
    // Re-evaluate at step/branch boundaries, not on every streaming text chunk.
    val projection = remember(messageIds, summaries, settings.enabled, recall, inspection?.requestId) {
        if (settings.enabled) projectContext(contextUnits(messages.limitContext(0)), summaries, recall) else emptyList()
    }
    val activeSummaries = projection.mapNotNull { it.summary }
    val tools = remember(messageIds, batches, summaries, inspection?.requestId, settings, recall) {
        if (!settings.prune || !recall) emptyMap() else projection.filter { it.summary == null }.flatMap { item ->
            item.message.getTools().filter { isPrunableTool(it, settings) }.map {
                (item.message.id.toString() to it.toolCallId) to it
            }
        }.toMap()
    }
    val activeBatches = remember(batches, tools) {
        val seen = mutableSetOf<Pair<String, String>>()
        batches.mapNotNull { batch ->
            batch.copy(entries = batch.entries.filter {
                val key = it.messageId to it.toolCallId
                key in tools && seen.add(key)
            }).takeIf { it.entries.isNotEmpty() }
        }
    }
    val snapshot = inspection?.takeIf { messageIds.take(it.messageIds.size) == it.messageIds }
    val sameConfig = snapshot != null && snapshot.modelId == model?.id &&
        snapshot.assistant == assistant && snapshot.settings == settings
    val lastMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT && it.lastRequestUsage() != null }
    val usage = snapshot?.usage ?: lastMessage?.lastRequestUsage()
    val budget = remember(model, assistant.maxTokens, settings) {
        model?.let { runCatching { contextBudget(it.contextLength, assistant.maxTokens, settings) }.getOrNull() }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("上下文洞察", style = MaterialTheme.typography.headlineSmallEmphasized)
                TextButton(onClick = onSettings) { Text("配置上下文管理") }
            }
            item {
                SectionTitle("最近完成的聊天请求")
                CardGroup {
                    if (usage == null) {
                        value("暂无用量数据", "发送消息后查看服务端返回的实际用量")
                    } else {
                        val prompt = usage.promptTokens
                        val cached = usage.cachedTokens.coerceIn(0, prompt.coerceAtLeast(0))
                        value("实际输入", prompt.formatNumber() + " tokens")
                        value("缓存命中", cached.formatNumber() + " tokens")
                        value("未命中", (prompt - cached).coerceAtLeast(0).formatNumber() + " tokens")
                        value("命中率", if (prompt > 0) "%.1f%%".format(cached * 100.0 / prompt) else "暂无数据")
                        val name = if (snapshot?.usage != null) snapshot.modelName else lastMessage?.modelSnapshot?.displayName
                        if (name != null) value("模型", name)
                    }
                }
            }
            item {
                SectionTitle("请求构成 · 本地估算")
                if (snapshot == null) {
                    Text("本次打开后尚无请求构成数据。已有实际用量、裁剪记录和摘要仍可查看。")
                } else {
                    Text("${snapshot.modelName} · ${formatTime(snapshot.createdAt)}", style = MaterialTheme.typography.bodySmall)
                    if (!sameConfig) Text("配置已变化，以下为上次请求的构成。", style = MaterialTheme.typography.bodySmall)
                    TokenBreakdown(snapshot.breakdown)
                }
            }
            item {
                SectionTitle("当前预算")
                CardGroup {
                    value("模型窗口", model?.contextLength?.takeIf { it > 0 }?.formatNumber() ?: "未配置")
                    if (settings.enabled && budget != null) {
                        value("可用输入", budget.input.formatNumber())
                        value("输出预留", budget.output.formatNumber())
                        value("压缩触发 / 目标", "${budget.trigger.formatNumber()} / ${budget.target.formatNumber()}")
                    } else if (!settings.enabled) {
                        value("增强上下文管理", "未启用")
                    } else {
                        value("输入预算", "请先配置模型上下文容量")
                    }
                }
            }
            item {
                SectionTitle("工具结果裁剪")
                val entries = activeBatches.flatMap { it.entries }
                CardGroup {
                    value("当前生效", "${entries.size} 个结果")
                    value("估算释放", entries.sumOf { it.tokensSaved.toLong() }.formatTokenCount() + " tokens")
                    if (snapshot != null && sameConfig && settings.prune && recall) {
                        value("下一批新增候选 · 上次准备时", "${snapshot.candidateTokens.formatNumber()} / ${snapshot.minimumTokens.formatNumber()}")
                    }
                }
                if (entries.isEmpty()) Text("当前分支没有生效的工具裁剪记录。", style = MaterialTheme.typography.bodySmall)
            }
            activeBatches.forEach { batch ->
                item(key = batch.id) {
                    var expanded by remember(batch.id) { mutableStateOf(false) }
                    CardGroup {
                        item(
                            onClick = { expanded = !expanded },
                            headlineContent = { Text("${batch.entries.size} 个结果 · ${formatTime(batch.createdAt)}") },
                            supportingContent = { Text(if (batch.underPressure) "上下文压力触发" else "达到新增裁剪阈值") },
                        )
                        if (expanded) batch.entries.forEach { entry ->
                            formItem(headlineContent = { Text(tools[entry.messageId to entry.toolCallId]?.toolName.orEmpty()) }) {
                                var showOriginal by remember(entry.toolCallId) { mutableStateOf(false) }
                                TextButton(onClick = { showOriginal = !showOriginal }) {
                                    Text(if (showOriginal) "收起原始结果" else "查看原始结果")
                                }
                                if (showOriginal) SelectionContainer {
                                    Text(tools[entry.messageId to entry.toolCallId]?.output
                                        ?.filterIsInstance<UIMessagePart.Text>()?.joinToString("\n") { it.text }.orEmpty())
                                }
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("当前分支的历史摘要")
                if (activeSummaries.isEmpty()) Text("暂无生效摘要。", style = MaterialTheme.typography.bodySmall)
            }
            items(activeSummaries, key = { it.id }) { summary ->
                var expanded by remember(summary.id) { mutableStateOf(false) }
                val positions = summary.sources.mapNotNull { source ->
                    messages.indexOfFirst { it.id.toString() == source.messageId }.takeIf { it >= 0 }?.plus(1)
                }
                CardGroup {
                    item(
                        onClick = { expanded = !expanded },
                        headlineContent = { Text("第 ${positions.minOrNull()}–${positions.maxOrNull()} 条消息") },
                        supportingContent = { Text("约 ${summary.tokensBefore.formatNumber()} → ${summary.tokensAfter.formatNumber()} tokens") },
                    )
                    if (expanded) formItem {
                        SelectionContainer { Text(summary.text) }
                    }
                }
            }
            item {
                Text("构成和释放量为本地估算，缓存用量由服务端返回。原始消息与工具结果仍然保留。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

private fun CardGroupScope.value(label: String, value: String) = item(
    headlineContent = { Text(label) },
    supportingContent = { Text(value) },
)

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmallEmphasized)
}

@Composable
private fun TokenBreakdown(breakdown: Map<String, Int>) {
    val colors = with(MaterialTheme.colorScheme) {
        listOf(primary, secondary, tertiary, primaryContainer, secondaryContainer, tertiaryContainer, outline)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (breakdown.isNotEmpty()) Row(Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.large)) {
            breakdown.entries.forEachIndexed { index, entry ->
                Box(Modifier.weight(entry.value.toFloat()).height(12.dp).background(colors[index % colors.size]))
            }
        }
        CardGroup {
            breakdown.forEach { (label, tokens) -> value(label, tokens.formatNumber()) }
            value("合计", breakdown.values.sumOf { it.toLong() }.formatTokenCount() + " tokens")
        }
    }
}

private fun formatTime(time: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time))

private fun Long.formatTokenCount(): String = NumberFormat.getIntegerInstance().format(this)
