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
import me.rerere.rikkahub.data.ai.context.contextBudget
import me.rerere.rikkahub.data.ai.context.contextPruneBudget
import me.rerere.rikkahub.data.datastore.getCurrentAssistant
import me.rerere.rikkahub.data.datastore.getCurrentChatModel
import me.rerere.rikkahub.data.model.ContextManagementSettings
import me.rerere.rikkahub.ui.components.nav.BackButton
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.CardGroupValue
import me.rerere.rikkahub.ui.theme.CustomColors
import me.rerere.rikkahub.utils.plus
import org.koin.androidx.compose.koinViewModel

@Composable
fun SettingContextAdvancedPage(vm: SettingVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val config = settings.contextManagement
    val currentModel = if (settings.assistants.isNotEmpty()) settings.getCurrentChatModel() else null
    val budget = currentModel?.let { model ->
        runCatching { contextBudget(model.contextLength, settings.getCurrentAssistant().maxTokens, config) }.getOrNull()
    }
    val pruneBudget = budget?.let { contextPruneBudget(it.input, config) }
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val summaryOutput = budget?.summary
    fun update(transform: (ContextManagementSettings) -> ContextManagementSettings) {
        vm.updateSettings { latest ->
            latest.copy(
                contextManagement = transform(latest.contextManagement).normalized()
            )
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("高级设置") },
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
                    item(
                        headlineContent = {
                            Text("预算参考模型：" + (currentModel?.displayName?.ifBlank { currentModel.modelId } ?: "未选择"))
                        },
                        supportingContent = {
                            Text(
                                budget?.let {
                                    "输入预算 " + it.input + " tokens；达到 " + it.trigger + " 时触发，目标降至 " + it.target + "。不同会话按各自模型生效。"
                                } ?: "请先正确配置聊天模型的上下文容量和回复长度。"
                            )
                        },
                    )
                }
            }
            item {
                CardGroup(title = { Text("压缩时机与预算") }) {
                    formItem(
                        headlineContent = { Text("压缩触发比例") },
                        trailingContent = { CardGroupValue(config.triggerPercent.toString() + "%") },
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
                        headlineContent = { Text("压缩后目标比例") },
                        trailingContent = { CardGroupValue(config.targetPercent.toString() + "%") },
                        supportingContent = { Text("默认 65%，至少低于触发比例 5 个百分点。启动后尽量压缩到此占用；近期保留内容和固定提示无法缩减时安全结束。") },
                    ) {
                        Slider(
                            value = config.targetPercent.toFloat(),
                            onValueChange = { value -> update { it.copy(targetPercent = value.toInt()) } },
                            valueRange = 10f..(config.triggerPercent - 5).toFloat(),
                            enabled = config.enabled,
                        )
                    }
                    formItem(
                        headlineContent = { Text("单批处理历史预算") },
                        supportingContent = {
                            Text(
                                "0 为自动：约占可用输入预算的 1/3，随上下文窗口等比例增长。手动值最多为全部输入预算；完整消息和工具结果不拆开，实际分段可能略大。"
                            )
                        },
                    ) {
                        ContextTokenField(
                            config.compressionChunkTokens,
                            config.enabled,
                            0..1_000_000,
                            budget?.chunk
                        ) { value ->
                            update { it.copy(compressionChunkTokens = value) }
                        }
                    }
                    formItem(
                        headlineContent = { Text("最近原文保留预算") },
                        supportingContent = { Text("0 为自动：约占可用输入预算的 1/5，随上下文窗口等比例增长。工具调用与结果保持完整。") },
                    ) {
                        ContextTokenField(
                            config.recentTokens,
                            config.enabled,
                            0..1_000_000,
                            budget?.recent
                        ) { value ->
                            update { it.copy(recentTokens = value) }
                        }
                    }
                    formItem(
                        headlineContent = { Text("摘要长度预算") },
                        supportingContent = { Text("0 为自动：约占可用输入预算的 1/12，摘要模型输出上限仍会生效。更大的摘要能保留更多细节，也会占用更多上下文。") },
                    ) {
                        ContextTokenField(
                            config.summaryTokens,
                            config.enabled,
                            0..32_768,
                            summaryOutput
                        ) { value ->
                            update { it.copy(summaryTokens = value) }
                        }
                    }
                }
            }
            item {
                CardGroup(title = { Text("工具裁剪预算") }) {
                    item(
                        headlineContent = { Text("保护近期结果与指令") },
                        supportingContent = {
                            Text(
                                "保留最近两轮用户对话、技能、用户回答和媒体结果。长工具循环中，仅裁剪模型已读过的较早结果，最新一批完整保留。裁剪独立于自动摘要开关。"
                            )
                        },
                    )
                    formItem(
                        headlineContent = { Text("额外保留的工具结果预算") },
                        supportingContent = {
                            Text(
                                "在上述保护范围外，再保留一段近期工具结果。0 为自动：约占可用输入预算的 2/5，随上下文窗口等比例增长；手动设置不超过输入预算的一半。"
                            )
                        },
                    ) {
                        ContextTokenField(
                            config.pruneProtectTokens,
                            config.enabled && config.prune,
                            0..1_000_000,
                            effective = pruneBudget?.protect
                        ) { value ->
                            update { it.copy(pruneProtectTokens = value) }
                        }
                    }
                    formItem(
                        headlineContent = { Text("单批最少释放量") },
                        supportingContent = {
                            Text(
                                "平时达到此净节省量才裁剪，避免频繁改动请求前缀。达到压缩触发比例时允许小批裁剪，仍超限才摘要。0 为自动：约占可用输入预算的 1/5；手动设置不超过输入预算的一半。"
                            )
                        },
                    ) {
                        ContextTokenField(
                            config.pruneMinimumTokens,
                            config.enabled && config.prune,
                            0..1_000_000,
                            effective = pruneBudget?.minimum
                        ) { value ->
                            update { it.copy(pruneMinimumTokens = value) }
                        }
                    }
                    formItem(
                        headlineContent = { Text("额外保护的工具名称") },
                        supportingContent = { Text("填写完整名称，每行一个或用逗号分隔。skill、use_skill、ask_user 始终保护。") },
                    ) {
                        var draft by remember(config.pruneProtectedTools) {
                            mutableStateOf(config.pruneProtectedTools.joinToString("\n"))
                        }
                        OutlinedTextField(
                            value = draft,
                            onValueChange = { input ->
                                draft = input
                                val names = input.split(
                                    '\n',
                                    ',',
                                    '，'
                                ).map(String::trim).filter(String::isNotEmpty).distinct()
                                update { it.copy(pruneProtectedTools = names) }
                            },
                            enabled = config.enabled && config.prune,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("工具名称") },
                            minLines = 2,
                        )
                    }
                }
            }
            item {
                CardGroup(title = { Text("历史回查预算") }) {
                    formItem(
                        headlineContent = { Text("单次回查返回预算") },
                        supportingContent = { Text("返回结果支持分页，实际预算也会根据模型窗口缩小。") },
                    ) {
                        ContextTokenField(
                            config.recallTokens,
                            config.enabled && config.historyTools,
                            256..32_768,
                            currentModel?.let {
                                minOf(
                                    config.recallTokens,
                                    (it.contextLength / 8).coerceAtLeast(256)
                                )
                            }
                        ) { value ->
                            update { it.copy(recallTokens = value) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ContextTokenField(
    value: Int,
    enabled: Boolean,
    range: IntRange,
    effective: Int? = null,
    onChange: (Int) -> Unit,
) {
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
        label = { Text(if (range.first == 0) "Tokens（0 为自动）" else "Tokens") },
        isError = draft.isNotEmpty() && !valid,
        supportingText = {
            if (!valid && draft.isNotEmpty()) {
                Text("请输入 " + range.first + "–" + range.last)
            } else if (effective != null) {
                Text("参考模型生效值：" + effective + " tokens")
            }
        },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    )
}
