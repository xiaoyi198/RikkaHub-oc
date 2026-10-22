package me.rerere.rikkahub.service

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.rerere.rikkahub.AppScope
import me.rerere.rikkahub.data.db.AppDatabase
import me.rerere.rikkahub.data.db.entity.WorkspaceTaskEntity
import me.rerere.rikkahub.data.files.FilesManager
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.utils.JsonInstant
import me.rerere.workspace.WorkspaceTask
import me.rerere.workspace.WorkspaceTaskEngine
import java.io.File
import java.net.URI

class WorkspaceTaskManager(
    private val context: Context,
    appScope: AppScope,
    repository: WorkspaceRepository,
    database: AppDatabase,
    filesManager: FilesManager,
) {
    private val dao = database.workspaceTaskDao()
    val engine = WorkspaceTaskEngine(
        scope = appScope,
        directory = File(context.filesDir, "workspace_tasks"),
        spawn = { repository.startLocalCommand(it.workspaceId, it.command, it.cwd) },
        persist = { task ->
            dao.save(
                WorkspaceTaskEntity(task.id, task.conversationId, task.workspaceId, JsonInstant.encodeToString(task))
            )
        },
        removeRecord = { dao.delete(it) },
    )
    val tasks = engine.tasks
    private val ready = appScope.async(Dispatchers.IO) {
        engine.restore(dao.getAll().map { JsonInstant.decodeFromString<WorkspaceTask>(it.payload) })
    }

    init {
        appScope.launch(Dispatchers.IO) {
            ready.await()
            while (true) {
                engine.cleanupLogs()
                delay(60L * 60 * 1000)
            }
        }
        filesManager.isTaskFile = { file ->
            val path = file.canonicalFile
            tasks.value.values.any { task ->
                task.active && task.attachmentFiles.any { url ->
                    runCatching {
                        File(
                            URI(url)
                        ).canonicalFile
                    }.getOrNull()?.let { it == path || it.startsWith(path) } ==
                        true
                }
            }
        }
        repository.beforeWorkspaceMutation = { id ->
            ready.await()
            engine.blockWorkspace(id)
        }
        repository.afterWorkspaceMutation = { id -> engine.unblockWorkspace(id) }
    }

    suspend fun awaitReady() {
        ready.await()
    }

    suspend fun start(task: WorkspaceTask): WorkspaceTask {
        ready.await()
        val started = engine.start(task)
        if (!WorkspaceTaskForegroundService.ensureStarted(context)) {
            engine.stop(started.id, started.conversationId)
            error("Unable to keep workspace tasks running. Please keep the app in the foreground and retry.")
        }
        return started
    }
}
