package me.rerere.rikkahub.data.ai.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.service.WorkspaceTaskManager
import me.rerere.workspace.WorkspaceTask
import me.rerere.workspace.WorkspaceTaskStatus

internal fun workspaceTaskJson(task: WorkspaceTask) = buildJsonObject {
    put("taskId", task.id)
    put("status", task.status.name.lowercase())
    put("background", task.background)
    put("active", task.active)
    put("startedAt", task.startedAt)
    task.finishedAt?.let { put("finishedAt", it) }
    put("command", task.command)
    put("cwd", task.cwd)
    put("stdout", task.output)
    put("truncated", task.truncated)
    put("timedOut", task.status == WorkspaceTaskStatus.TIMED_OUT)
    task.exitCode?.let { put("exitCode", it) }
    if (task.active && task.background) put("notice", "Task continues in the background. Its result will be delivered automatically. Do not poll for completion.")
}

internal fun createWorkspaceTaskTools(manager: WorkspaceTaskManager, owner: String): List<Tool> = listOf(
    Tool(
        name = "workspace_task_list",
        description = "List workspace commands owned by this conversation, including running and recently finished tasks.",
        parameters = { InputSchema.Obj(buildJsonObject { }) },
        execute = {
            manager.awaitReady()
            listOf(UIMessagePart.Text(JsonArray(manager.tasks.value.values.filter { it.conversationId == owner }
                .sortedWith(compareByDescending<WorkspaceTask> { it.active }.thenByDescending { it.startedAt }).take(50).map { workspaceTaskJson(it.copy(output = "", command = it.command.take(200))) }).toString()))
        },
    ),
    Tool(
        name = "workspace_task_output",
        description = "Read a task's incremental output using its returned cursor. Optionally wait up to 30 seconds. Waiting never cancels execution. Completion is automatic; do not repeatedly poll.",
        parameters = { taskSchema(output = true) },
        execute = {
            manager.awaitReady()
            val args = it.jsonObject
            val id = args["taskId"]?.jsonPrimitive?.contentOrNull ?: error("taskId is required")
            val cursor = args["cursor"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
            val wait = args["wait"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0
            require(cursor >= 0 && wait in 0..30) { "Invalid output cursor or wait" }
            val page = manager.engine.output(id, owner, cursor, wait * 1000)
            listOf(UIMessagePart.Text(buildJsonObject {
                put("taskId", id)
                put("status", manager.engine.get(id, owner).status.name.lowercase())
                put("output", page.output)
                put("cursor", page.cursor)
                put("size", page.size)
                put("truncated", page.truncated)
            }.toString()))
        },
    ),
    Tool(
        name = "workspace_task_stop",
        description = "Stop a command owned by this conversation and clean up its managed processes. Use when a background task is no longer needed.",
        parameters = { taskSchema(output = false) },
        execute = {
            manager.awaitReady()
            val id = it.jsonObject["taskId"]?.jsonPrimitive?.contentOrNull ?: error("taskId is required")
            listOf(UIMessagePart.Text(workspaceTaskJson(manager.engine.stop(id, owner)).toString()))
        },
    ),
)

private fun taskSchema(output: Boolean) = InputSchema.Obj(
    properties = buildJsonObject {
        put("taskId", buildJsonObject { put("type", "string") })
        if (output) {
            put("cursor", buildJsonObject { put("type", "integer") })
            put("wait", buildJsonObject {
                put("type", "integer")
                put("description", "Seconds to wait, 0 through 30")
            })
        }
    },
    required = listOf("taskId"),
)
