package me.rerere.rikkahub.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.ai.provider.ModelType
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.ui.components.ai.ModelField
import me.rerere.rikkahub.ui.components.ai.ModelListSheet
import me.rerere.rikkahub.ui.components.ai.rememberModelListState
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.components.ui.switchItem
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingContextPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.contextManagement
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val summaryModel = rememberModelListState(config.summaryModelId, settings.providers, ModelType.CHAT)
    fun update(transform: (ContextManagementSettings) -> ContextManagementSettings) {
        vm.updateSettings { latest -> latest.copy(contextManagement = transform(latest.contextManagement)) }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("上下文管理") },
                navigationIcon = { BackButton() },
                scrollBehavior = scroll,
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding + PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                CardGroup {
                    switchItem(
                        checked = config.enabled,
                        onCheckedChange = { enabled -> update { it.copy(enabled = enabled) } },
                        headlineContent = { Text("启用增强上下文管理") },
                        supportingContent = { Text("按 token 预算压缩历史，并支持找回原文。关闭后恢复原有消息条数限制和手动压缩逻辑；修改从下一轮生成生效。") },
                    )
                    item(
                        headlineContent = { Text(if (config.enabled) "增强策略已启用" else "当前使用原有策略") },
                        supportingContent = { Text("原始消息始终保留。摘要独立保存；开启增强策略时不再叠加助手的消息条数截断。请在模型设置中填写准确的上下文容量。") },
                    )
                }
            }
            item {
                CardGroup(title = { Text("压缩时机与预算") }) {
                    switchItem(
                        checked = config.autoCompress,
                        enabled = config.enabled,
                        onCheckedChange = { value -> update { it.copy(autoCompress = value) } },
                        headlineContent = { Text("自动压缩") },
                        supportingContent = { Text("在新请求和多轮工具调用之间检查预算；关闭后可手动压缩。") },
                    )
                    formItem(
                        headlineContent = { Text("压缩触发比例") },
                        trailingContent = { CardGroupValue("${config.triggerPercent}%") },
                        supportingContent = { Text("同时预留回复空间与安全余量；实际触发点取更保守的预算。") },
                    ) {
                        Slider(
                            value = config.triggerPercent.toFloat(),
                            onValueChange = { value -> update { it.copy(triggerPercent = value.toInt()) } },
                            valueRange = 50f..95f,
                            steps = 8,
                            enabled = config.enabled,
                        )
                    }
                    formItem(
                        headlineContent = { Text("最近原文保留预算") },
                        supportingContent = { Text("0 为自动：可用输入预算的约 20%，最多 20,000 tokens。小窗口会自动缩小，工具调用与结果保持完整。") },
                    ) {
                        ContextTokenField(config.recentTokens, config.enabled, 0..1_000_000) { value ->
                            update { it.copy(recentTokens = value) }
                        }
                    }
                    formItem(
                        headlineContent = { Text("摘要长度预算") },
                        supportingContent = { Text("0 为自动。数值越大，可保留更多细节，也会占用更多上下文。") },
                    ) {
                        ContextTokenField(config.summaryTokens, config.enabled, 0..32_768) { value ->
                            update { it.copy(summaryTokens = value) }
                        }
                    }
                }
            }
            item {
                CardGroup(title = { Text("摘要模型") }) {
                    switchItem(
                        checked = config.summaryModelId == null,
                        enabled = config.enabled,
                        onCheckedChange = { follow ->
                            if (follow) update { it.copy(summaryModelId = null) } else summaryModel.open()
                        },
                        headlineContent = { Text("使用当前聊天模型") },
                        supportingContent = { Text("摘要会调用模型并产生用量。也可以选择独立模型，摘要请求会按该模型的容量分段。") },
                    )
                    if (config.enabled) {
                        formItem(headlineContent = { Text("选择独立摘要模型") }) {
                            ModelField(summaryModel.currentModel, onClick = { summaryModel.open() })
                        }
                    }
                }
            }
            item {
                CardGroup(title = { Text("历史回查") }) {
                    switchItem(
                        checked = config.historyTools,
                        enabled = config.enabled,
                        onCheckedChange = { value -> update { it.copy(historyTools = value) } },
                        headlineContent = { Text("允许模型查找历史原文") },
                        supportingContent = { Text("提供 history_search / history_read，只访问当前对话的选中分支，包含工具结果。需要聊天模型支持工具调用。") },
                    )
                    formItem(
                        headlineContent = { Text("单次回查返回预算") },
                        supportingContent = { Text("返回结果支持分页，实际预算也会根据模型窗口缩小。") },
                    ) {
                        ContextTokenField(config.recallTokens, config.enabled, 256..32_768) { value ->
                            update { it.copy(recallTokens = value) }
                        }
                    }
                }
            }
            item {
                CardGroup {
                    item(
                        onClick = { update { ContextManagementSettings() } },
                        headlineContent = { Text("恢复默认设置") },
                        supportingContent = { Text("关闭增强策略并重置预算，保留聊天记录和已有摘要。") },
                    )
                }
            }
        }
    }
    ModelListSheet(summaryModel) { model -> update { it.copy(summaryModelId = model.id) } }
}

@Composable
private fun ContextTokenField(value: Int, enabled: Boolean, range: IntRange, onChange: (Int) -> Unit) {
    var draft by remember(value) { mutableStateOf(value.toString()) }
    val valid = draft.toIntOrNull()?.let { it in range } == true
    OutlinedTextField(
        value = draft,
        onValueChange = { input ->
            if (input.length <= 7 && input.all(Char::isDigit)) {
                draft = input
                input.toIntOrNull()?.takeIf { it in range }?.let(onChange)
            }
        },
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        label = { Text("Tokens") },
        isError = draft.isNotEmpty() && !valid,
        supportingText = { if (!valid && draft.isNotEmpty()) Text("请输入 ${range.first}–${range.last}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
