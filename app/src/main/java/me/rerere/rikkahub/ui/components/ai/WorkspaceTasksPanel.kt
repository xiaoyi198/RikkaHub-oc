@file:Suppress("ktlint:standard:function-naming")

package me.rerere.rikkahub.ui.components.ai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.rikkahub.service.WorkspaceTaskManager
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.ui.toast.ToastType
import me.rerere.workspace.WorkspaceTask
import me.rerere.workspace.WorkspaceTaskStatus
import org.koin.compose.koinInject

@Composable
fun WorkspaceTasksButton(conversationId: String, onClick: () -> Unit) {
    val manager = koinInject<WorkspaceTaskManager>()
    val tasks by manager.tasks.collectAsStateWithLifecycle()
    val owned = tasks.values.filter { it.conversationId == conversationId }
    val active = owned.count { it.active }
    val status = when {
        active > 0 -> "$active 个任务进行中"
        owned.isNotEmpty() -> "所有任务已结束"
        else -> "无任务"
    }
    val color = when {
        active > 0 -> Color(0xFF42A5F5)
        owned.isNotEmpty() -> Color(0xFF66BB6A)
        else -> MaterialTheme.colorScheme.outline
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { PlainTooltip { Text("工作区任务 · $status") } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.semantics { stateDescription = status }) {
            BadgedBox(badge = { Badge(containerColor = color) }) {
                Icon(HugeIcons.ComputerTerminal01, contentDescription = "工作区任务")
            }
        }
    }
}

/** A closed task drawer takes no space from the conversation or composer. */
@Composable
fun WorkspaceTasksDrawer(conversationId: String, drawerState: DrawerState, content: @Composable () -> Unit) {
    val layoutDirection = LocalLayoutDirection.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    BackHandler(drawerState.isOpen) { scope.launch { drawerState.close() } }
    LaunchedEffect(drawerState.isOpen) {
        if (drawerState.isOpen) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
        }
    }
    LaunchedEffect(conversationId) { drawerState.close() }
    // Material's drawer opens from the start edge; only its container uses RTL.
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = drawerState.isOpen,
            drawerContent = {
                CompositionLocalProvider(LocalLayoutDirection provides layoutDirection) {
                    ModalDrawerSheet(
                        modifier = Modifier.widthIn(max = 400.dp).fillMaxWidth(0.9f),
                        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 1f),
                        drawerShape = MaterialTheme.shapes.extraLarge.copy(
                            topEnd = CornerSize(0.dp),
                            bottomEnd = CornerSize(0.dp),
                        ),
                    ) {
                        WorkspaceTasksPanel(conversationId, onClose = { scope.launch { drawerState.close() } })
                    }
                }
            },
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides layoutDirection, content = content)
        }
    }
}

@Composable
private fun WorkspaceTasksPanel(conversationId: String, onClose: () -> Unit) {
    val manager = koinInject<WorkspaceTaskManager>()
    val tasks by manager.tasks.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val owned = remember(tasks, conversationId) {
        tasks.values.filter { it.conversationId == conversationId }
            .sortedWith(compareByDescending<WorkspaceTask> { it.active }.thenByDescending { it.startedAt })
    }
    val active = owned.count { it.active }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("工作区任务", style = MaterialTheme.typography.titleLarge)
                Text(
                    if (active >
                        0) {
                        "$active 个进行中 · ${owned.size - active} 个已结束"
                    } else if (owned.isNotEmpty()) {
                        "${owned.size} 个已结束"
                    } else {
                        "当前聊天暂无任务"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) { Icon(HugeIcons.Cancel01, contentDescription = "关闭任务抽屉") }
        }
        if (owned.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "任务启动后会显示在这里",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            return@Column
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(if (active >
                0) {
                "进行中"
            } else {
                "已结束"
            }, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            if (active > 1) {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            manager.engine.stopOwner(conversationId)
                        } catch (error: Exception) {
                            if (error is CancellationException) throw error
                            toaster.show(error.message ?: "停止任务失败", ToastType.Error)
                        }
                    }
                }) { Text("全部停止") }
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(owned.filter { it.active }, key = { it.id }) { task -> WorkspaceTaskCard(task, manager) }
            if (active > 0 && active < owned.size) {
                item("history") {
                    Text(
                        "已结束",
                        modifier = Modifier.padding(start = 4.dp, top = 12.dp, bottom = 4.dp),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            items(owned.filterNot { it.active }, key = { it.id }) { task -> WorkspaceTaskCard(task, manager) }
        }
    }
}

@Composable
fun WorkspaceTaskCard(task: WorkspaceTask, manager: WorkspaceTaskManager = koinInject()) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var busy by remember(task.id) { mutableStateOf(false) }
    var showLog by remember(task.id) { mutableStateOf(false) }
    var now by remember(task.id) { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(task.id, task.active) {
        while (task.active) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    fun action(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                toaster.show(error.message ?: "任务操作失败", ToastType.Error)
            } finally {
                busy = false
            }
        }
    }
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (task.active) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(HugeIcons.ComputerTerminal01, null, Modifier.size(18.dp))
                }
                Text(
                    task.status.label() + if (task.active && task.background) " · 后台" else "",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (task.status == WorkspaceTaskStatus.FAILED ||
                        task.status == WorkspaceTaskStatus.TIMED_OUT) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier.weight(1f),
                )
                val elapsed = ((task.finishedAt ?: now) - task.startedAt).coerceAtLeast(0) / 1000
                Text(
                    "${elapsed / 60}:${(elapsed % 60).toString().padStart(2, '0')}",
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                task.command,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { showLog = true }) { Text("日志") }
                if (task.active && !task.background && task.status != WorkspaceTaskStatus.STOPPING) {
                    TextButton(
                        enabled = !busy,
                        onClick = { action { manager.engine.background(task.id, task.conversationId) } }
                    ) { Text("转后台") }
                }
                if (task.active) {
                    TextButton(
                        enabled = !busy && task.status != WorkspaceTaskStatus.STOPPING,
                        onClick = { action { manager.engine.stop(task.id, task.conversationId) } }
                    ) {
                        Text(if (task.status ==
                            WorkspaceTaskStatus.STOPPING) {
                            "停止中…"
                        } else {
                            "停止"
                        }, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
    if (showLog) {
        ModalBottomSheet(onDismissRequest = { showLog = false }) {
            WorkspaceTaskLog(task.id, task.conversationId, manager)
        }
    }
}

@Composable
fun WorkspaceTaskLog(taskId: String, owner: String, manager: WorkspaceTaskManager = koinInject()) {
    val tasks by manager.tasks.collectAsStateWithLifecycle()
    val task = tasks[taskId]?.takeIf { it.conversationId == owner } ?: return
    val scope = rememberCoroutineScope()
    var page by remember(taskId) { mutableStateOf<String?>(null) }
    var cursor by remember(taskId) { mutableStateOf(0L) }
    var size by remember(taskId) { mutableStateOf(0L) }
    val toaster = LocalToaster.current
    Column(
        Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            task.status.label() + (task.exitCode?.let { " · exit $it" } ?: ""),
            style = MaterialTheme.typography.titleMedium
        )
        SelectionContainer { Text(task.command + "\n目录：/workspace/" + task.cwd, fontFamily = FontFamily.Monospace) }
        if (task.truncated) Text("日志文件已达 20 MiB，以下仍持续显示最新输出。", style = MaterialTheme.typography.bodySmall)
        Row {
            TextButton(onClick = { page = null }) { Text("最新输出") }
            TextButton(onClick = {
                scope.launch {
                    try {
                        val result = manager.engine.output(taskId, owner, if (page == null ||
                            cursor >= size) {
                            0
                        } else {
                            cursor
                        })
                        page = result.output
                        cursor = result.cursor
                        size = result.size
                    } catch (error: Exception) {
                        if (error is CancellationException) throw error
                        toaster.show(error.message ?: "读取日志失败", ToastType.Error)
                    }
                }
            }) { Text(if (page == null || cursor >= size) "从头读取" else "下一页") }
        }
        if (page != null) Text("$cursor / $size bytes", style = MaterialTheme.typography.labelSmall)
        SelectionContainer {
            Text(
                page ?: task.output.ifEmpty { "等待输出…" },
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
            )
        }
    }
}

private fun WorkspaceTaskStatus.label() = when (this) {
    WorkspaceTaskStatus.STARTING -> "启动中"
    WorkspaceTaskStatus.RUNNING -> "运行中"
    WorkspaceTaskStatus.STOPPING -> "停止中"
    WorkspaceTaskStatus.COMPLETED -> "已完成"
    WorkspaceTaskStatus.FAILED -> "失败"
    WorkspaceTaskStatus.CANCELLED -> "已停止"
    WorkspaceTaskStatus.TIMED_OUT -> "已超时"
    WorkspaceTaskStatus.INTERRUPTED -> "已中断"
}
