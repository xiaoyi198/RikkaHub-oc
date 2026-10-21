package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.ui.limitContext
import me.rerere.rikkahub.data.ai.context.contextUnits
import me.rerere.rikkahub.data.ai.context.projectContext
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.repository.ContextSummaryRepository
import me.rerere.rikkahub.ui.components.ui.CardGroup
import org.koin.compose.koinInject

@Composable
internal fun ContextSummarySheet(conversation: Conversation, onDismiss: () -> Unit, onSettings: () -> Unit) {
    val repository = koinInject<ContextSummaryRepository>()
    val flow = remember(repository, conversation.id) { repository.observe(conversation.id.toString()) }
    val summaries by flow.collectAsStateWithLifecycle(initialValue = emptyList())
    val active = remember(conversation.messageNodes, summaries) {
        projectContext(contextUnits(conversation.currentMessages.limitContext(0)), summaries).mapNotNull { it.summary }
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("上下文摘要", style = MaterialTheme.typography.titleLarge)
                    Text("以下摘要用于当前分支，原始消息仍然保留。开启历史回查且模型支持工具调用时，可找回细节。用量为估算值。")
                    TextButton(onClick = onSettings) { Text("配置上下文管理") }
                }
            }
            if (active.isEmpty()) item { Text("当前分支还没有有效摘要。达到预算阈值时会自动压缩，也可以手动压缩。") }
            items(active, key = { it.id }) { summary ->
                var expanded by remember(summary.id) { mutableStateOf(false) }
                val positions = summary.sources.mapNotNull { source ->
                    conversation.currentMessages.indexOfFirst { it.id.toString() == source.messageId }
                        .takeIf { it >= 0 }?.plus(1)
                }
                CardGroup {
                    item(
                        onClick = { expanded = !expanded },
                        headlineContent = { Text("第 ${positions.minOrNull()}–${positions.maxOrNull()} 条消息") },
                        supportingContent = { Text("约 ${summary.tokensBefore} → ${summary.tokensAfter} tokens · 点击${if (expanded) "收起" else "展开"}") },
                    )
                    if (expanded) formItem {
                        Text(summary.text, modifier = Modifier.fillMaxWidth())
                        Text("来源索引：${summary.id}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
