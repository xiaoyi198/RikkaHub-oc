package me.rerere.workspace

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Serializable
enum class WorkspaceTaskStatus {
    STARTING,
    RUNNING,
    STOPPING,
    COMPLETED,
    FAILED,
    CANCELLED,
    TIMED_OUT,
    INTERRUPTED;

    val active: Boolean get() = this == STARTING || this == RUNNING || this == STOPPING
}

@Serializable
data class WorkspaceTask(
    val id: String = UUID.randomUUID().toString(),
    val conversationId: String,
    val workspaceId: String,
    val sourceMessageId: String?,
    val attachmentFiles: List<String> = emptyList(),
    val command: String,
    val cwd: String = "",
    val background: Boolean = false,
    val timeoutMillis: Long = 30_000,
    val explicitTimeout: Boolean = false,
    val status: WorkspaceTaskStatus = WorkspaceTaskStatus.STARTING,
    val startedAt: Long = System.currentTimeMillis(),
    val finishedAt: Long? = null,
    val exitCode: Int? = null,
    val output: String = "",
    val truncated: Boolean = false,
    val delivered: Boolean = false,
    val resumeAllowed: Boolean = true,
) {
    val active: Boolean get() = status.active
}

data class WorkspaceTaskOutput(val output: String, val cursor: Long, val size: Long, val truncated: Boolean)

/**
 * Adapted from OpenCode c93aaae Job.start/block/background/cancel and Shell's file-backed output.
 * MIT copyright (c) 2025 opencode; see THIRD_PARTY_NOTICES.md. OS spawning and persistence are ports.
 * The owner scope runs the command; a tool only races completion against the background signal.
 */
class WorkspaceTaskEngine(
    private val scope: CoroutineScope,
    private val directory: File,
    private val spawn: suspend (WorkspaceTask) -> WorkspaceProcess,
    private val persist: suspend (WorkspaceTask) -> Unit,
    private val logLimit: Long = 20L * 1024 * 1024,
    private val removeRecord: suspend (String) -> Unit = {},
) {
    private class Execution {
        lateinit var job: Job
        val done = CompletableDeferred<Unit>()
        val backgrounded = CompletableDeferred<Unit>()

        @Volatile var deadline: Long? = null
    }
    private val mutex = Mutex()
    private val executions = ConcurrentHashMap<String, Execution>()
    private val blockedWorkspaces = mutableSetOf<String>()
    private val blockedOwners = mutableSetOf<String>()
    private val pausedOwners = mutableSetOf<String>()
    private val mutableTasks = MutableStateFlow<Map<String, WorkspaceTask>>(emptyMap())
    val tasks = mutableTasks.asStateFlow()

    suspend fun restore(records: List<WorkspaceTask>) = mutex.withLock {
        directory.mkdirs()
        records.forEach { record ->
            val tail = if (record.active) {
                runCatching {
                    val file = logFile(record.id)
                    if (!file.isFile) {
                        record.output
                    } else {
                        RandomAccessFile(file, "r").use { input ->
                            val count = minOf(input.length(), 32L * 1024).toInt()
                            input.seek(input.length() - count)
                            ByteArray(count).also { input.readFully(it) }.toString(Charsets.UTF_8)
                        }
                    }
                }.getOrDefault(record.output)
            } else {
                record.output
            }
            val restored = if (record.active) {
                record.copy(
                    status = WorkspaceTaskStatus.INTERRUPTED,
                    finishedAt = System.currentTimeMillis(),
                    output = tail + "\nCommand interrupted because the app restarted.",
                    resumeAllowed = false,
                )
            } else {
                record.copy(resumeAllowed = false)
            }
            val expired = !restored.active && restored.delivered &&
                System.currentTimeMillis() - (restored.finishedAt ?: restored.startedAt) > 7L * 24 * 60 * 60 * 1000
            if (expired) logFile(record.id).delete()
            mutableTasks.update { it + (restored.id to restored) }
            persist(restored)
        }
    }

    suspend fun cleanupLogs() = withContext(Dispatchers.IO) {
        tasks.value.values.filter {
            !it.active && it.delivered &&
                System.currentTimeMillis() - (it.finishedAt ?: it.startedAt) > 7L * 24 * 60 * 60 * 1000
        }.forEach { logFile(it.id).delete() }
    }

    private suspend fun change(id: String, transform: (WorkspaceTask) -> WorkspaceTask): WorkspaceTask = mutex.withLock {
        val current = tasks.value[id] ?: error("Task not found: $id")
        val next = transform(current)
        try {
            persist(next)
        } finally {
            mutableTasks.update { latest ->
                latest + (id to next.copy(
                    output = if (next.output == current.output) latest[id]?.output ?: next.output else next.output,
                    truncated = next.truncated || latest[id]?.truncated == true,
                ))
            }
        }
        tasks.value.getValue(id)
    }

    @OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
    suspend fun start(requested: WorkspaceTask): WorkspaceTask = mutex.withLock {
        val task = requested.copy(resumeAllowed = requested.resumeAllowed && requested.conversationId !in pausedOwners)
        check(task.workspaceId !in blockedWorkspaces && task.conversationId !in blockedOwners) { "Workspace or conversation is being removed" }
        val active = tasks.value.values.filter { it.active }
        check(active.size < 4 && active.count { it.conversationId == task.conversationId } < 2) {
            "Too many running tasks (maximum 2 per conversation and 4 in the app). Stop a task or wait for it to finish."
        }
        directory.mkdirs()
        persist(task)
        mutableTasks.update { it + (task.id to task) }
        val execution = Execution()
        execution.deadline = task.timeoutMillis.takeIf { it > 0 }?.let { System.nanoTime() + it * 1_000_000 }
        executions[task.id] = execution
        execution.job = scope.launch(Dispatchers.IO, start = CoroutineStart.ATOMIC) { run(task, execution) }
        task
    }

    fun get(id: String, owner: String): WorkspaceTask =
        tasks.value[id]?.takeIf { it.conversationId == owner } ?: error("Task not found in this conversation: $id")

    suspend fun await(id: String, owner: String): WorkspaceTask {
        get(id, owner)
        val execution = executions[id] ?: return get(id, owner)
        try {
            select<Unit> {
                execution.done.onAwait { }
                execution.backgrounded.onAwait { }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { if (!get(id, owner).background) stop(id, owner) }
            throw cancelled
        }
        return get(id, owner)
    }

    suspend fun background(id: String, owner: String): WorkspaceTask {
        get(id, owner)
        val task = change(id) { if (it.active) it.copy(background = true) else it }
        if (!task.explicitTimeout) executions[id]?.deadline = null
        if (task.background) executions[id]?.backgrounded?.complete(Unit)
        return task
    }

    suspend fun stop(id: String, owner: String): WorkspaceTask = withContext(NonCancellable) {
        val task = get(id, owner)
        if (!task.active) return@withContext task
        change(id) { if (it.active) it.copy(status = WorkspaceTaskStatus.STOPPING) else it }
        executions[id]?.job?.cancelAndJoin()
        get(id, owner)
    }

    suspend fun stopOwner(owner: String, block: Boolean = false) {
        if (block) mutex.withLock { blockedOwners.add(owner) }
        tasks.value.values.filter { it.conversationId == owner && it.active }.forEach { stop(it.id, owner) }
    }

    suspend fun forgetOwner(owner: String) = mutex.withLock {
        val owned = tasks.value.values.filter { it.conversationId == owner }
        check(owned.none { it.active }) { "Stop tasks before removing their history" }
        owned.forEach { task ->
            removeRecord(task.id)
            logFile(task.id).delete()
        }
        mutableTasks.update { it - owned.map { task -> task.id }.toSet() }
    }

    suspend fun blockWorkspace(id: String) {
        mutex.withLock { blockedWorkspaces.add(id) }
        tasks.value.values.filter { it.workspaceId == id && it.active }.forEach { stop(it.id, it.conversationId) }
    }

    suspend fun unblockWorkspace(id: String) {
        mutex.withLock { blockedWorkspaces.remove(id) }
    }
    suspend fun unblockOwner(id: String) {
        mutex.withLock { blockedOwners.remove(id) }
    }
    suspend fun pauseOwner(owner: String) {
        mutex.withLock { pausedOwners.add(owner) }
        tasks.value.values.filter { it.conversationId == owner && !it.delivered }.forEach { task ->
            change(task.id) { it.copy(resumeAllowed = false) }
        }
    }
    suspend fun resumeOwner(owner: String) {
        mutex.withLock { pausedOwners.remove(owner) }
        tasks.value.values.filter { it.conversationId == owner && !it.delivered }.forEach { task ->
            change(task.id) { it.copy(resumeAllowed = true) }
        }
    }
    suspend fun acknowledge(id: String, owner: String) {
        get(id, owner)
        change(id) { it.copy(delivered = true) }
    }

    suspend fun output(id: String, owner: String, cursor: Long = 0, waitMillis: Long = 0): WorkspaceTaskOutput {
        get(id, owner)
        if (waitMillis > 0) executions[id]?.let { withTimeoutOrNull(waitMillis.coerceAtMost(30_000)) { it.done.await() } }
        return withContext(Dispatchers.IO) {
            val file = logFile(id)
            val size = file.takeIf { it.isFile }?.length() ?: 0
            val start = cursor.coerceIn(0, size)
            val bytes = ByteArray(minOf(64L * 1024, size - start).toInt())
            if (bytes.isNotEmpty()) {
                RandomAccessFile(file, "r").use {
                    it.seek(start)
                    it.readFully(bytes)
                }
            }
            // Keep the next cursor on a UTF-8 character boundary.
            var end = bytes.size
            if (start + end < size) {
                while (end > 0 && bytes[end - 1].toInt() and 0xC0 == 0x80) end--
                if (end > 0 && bytes[end - 1].toInt() and 0x80 != 0) end-- else end = bytes.size
            }
            WorkspaceTaskOutput(bytes.copyOf(end).toString(Charsets.UTF_8), start + end, size, get(id, owner).truncated)
        }
    }

    private fun logFile(id: String) = File(directory, "$id.log")

    private suspend fun run(initial: WorkspaceTask, execution: Execution) {
        var process: WorkspaceProcess? = null
        var status = WorkspaceTaskStatus.FAILED
        var exit: Int? = null
        var failure: String? = null
        val readers = mutableListOf<Job>()
        val log = logFile(initial.id)
        try {
            withContext(NonCancellable) { process = spawn(initial) }
            process!!.input.close()
            change(initial.id) { it.copy(status = WorkspaceTaskStatus.RUNNING) }
            val captured = process
            readers += listOf(captured.stdout, captured.stderr).map { stream ->
                scope.launch(Dispatchers.IO) {
                    runCatching {
                        stream.bufferedReader().use { reader ->
                            val buffer = CharArray(4096)
                            var pending = ""
                            while (true) {
                                val count = reader.read(buffer)
                                if (count < 0) break
                                val decoded = pending + String(buffer, 0, count)
                                pending = if (decoded.lastOrNull()?.isHighSurrogate() == true) decoded.takeLast(1) else ""
                                val text = if (pending.isNotEmpty()) decoded.dropLast(1) else decoded
                                synchronized(log) {
                                    val bytes = text.toByteArray()
                                    val remaining = (logLimit - log.length()).coerceAtLeast(0).toInt()
                                    var end = minOf(remaining, bytes.size)
                                    if (end < bytes.size) while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
                                    if (end > 0) log.appendBytes(bytes.copyOf(end))
                                    mutableTasks.update { latest ->
                                        val task = latest[initial.id] ?: return@update latest
                                        latest + (task.id to task.copy(
                                            output = (task.output + text).takeLast(32 * 1024).let { tail ->
                                                if (tail.firstOrNull()?.isLowSurrogate() == true) tail.drop(1) else tail
                                            },
                                            truncated = task.truncated || bytes.size > remaining,
                                        ))
                                    }
                                }
                            }
                        }
                    }
                }
            }
            while (true) {
                exit = captured.poll()
                if (exit != null) {
                    status = if (exit == 0) WorkspaceTaskStatus.COMPLETED else WorkspaceTaskStatus.FAILED
                    break
                }
                if (execution.deadline?.let { System.nanoTime() >= it } == true) {
                    status = WorkspaceTaskStatus.TIMED_OUT
                    break
                }
                delay(25)
            }
        } catch (cancelled: CancellationException) {
            status = WorkspaceTaskStatus.CANCELLED
        } catch (error: Exception) {
            failure = error.message ?: error.toString()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try {
                    if (exit == null && process != null) {
                        runCatching { process.terminate(false) }
                        withTimeoutOrNull(3_000) { while (process.poll() == null) delay(25) }
                        runCatching { process.terminate(true) }
                        withTimeoutOrNull(2_000) { while (process.poll() == null) delay(25) }
                    }
                    withTimeoutOrNull(2_000) { readers.forEach { it.join() } }
                    runCatching { process?.close() }
                    readers.forEach { it.cancel() }
                    change(initial.id) { task ->
                        task.copy(
                            status = status,
                            finishedAt = System.currentTimeMillis(),
                            exitCode = exit,
                            delivered = task.delivered || !task.background,
                            output = if (failure == null) task.output else task.output + "\n$failure",
                        )
                    }
                } finally {
                    runCatching { process?.close() }
                    readers.forEach { it.cancel() }
                    execution.done.complete(Unit)
                    executions.remove(initial.id, execution)
                }
            }
        }
    }
}
