package me.rerere.workspace

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceTaskEngineTest {
    private val directory = Files.createTempDirectory("workspace-tasks").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, FakeProcess>()
    private val saved = ConcurrentHashMap<String, WorkspaceTask>()
    private var spawnCount = AtomicInteger()
    private val engine = WorkspaceTaskEngine(scope, directory, spawn = { task ->
        spawnCount.incrementAndGet()
        FakeProcess().also { processes[task.id] = it }
    }, persist = { saved[it.id] = it })

    private fun task(owner: String = "owner", background: Boolean = false, timeout: Long = 0) =
        WorkspaceTask(conversationId = owner, workspaceId = "workspace", sourceMessageId = "message", command = "work", background = background, timeoutMillis = timeout)

    private suspend fun running(id: String) = withTimeout(5_000) {
        engine.tasks.first { it[id]?.status == WorkspaceTaskStatus.RUNNING }
    }
    private suspend fun finished(id: String) = withTimeout(5_000) {
        engine.tasks.first { it[id]?.active == false }.getValue(id)
    }

    @After fun cleanup() = runBlocking<Unit> {
        engine.tasks.value.values.filter { it.active }.forEach { engine.stop(it.id, it.conversationId) }
        scope.cancel()
        directory.deleteRecursively()
    }

    @Test fun promotionReleasesWaiterWithoutRestartAndClearsDefaultDeadline() = runBlocking {
        val task = engine.start(task(timeout = 300))
        running(task.id)
        val waiter = async { engine.await(task.id, task.conversationId) }
        engine.background(task.id, task.conversationId)
        assertTrue(withTimeout(2_000) { waiter.await() }.background)
        delay(350)
        assertTrue(engine.get(task.id, "owner").active)
        assertEquals(1, spawnCount.get())
        processes.getValue(task.id).exit = 0
        assertEquals(WorkspaceTaskStatus.COMPLETED, finished(task.id).status)
    }

    @Test fun cancelledForegroundWaiterStopsCommandButBackgroundWaiterDoesNot() = runBlocking {
        val foreground = engine.start(task())
        running(foreground.id)
        val waiter = async { engine.await(foreground.id, "owner") }
        delay(30)
        waiter.cancelAndJoin()
        assertEquals(WorkspaceTaskStatus.CANCELLED, engine.get(foreground.id, "owner").status)
        assertTrue(processes.getValue(foreground.id).closed)
        val background = engine.start(task(background = true))
        running(background.id)
        val observer = async { engine.await(background.id, "owner") }
        delay(30)
        observer.cancelAndJoin()
        assertTrue(engine.get(background.id, "owner").active)
    }

    @Test fun explicitDeadlineSurvivesPromotionAndReadWaitNeverStopsCommand() = runBlocking {
        val task = engine.start(task(timeout = 200).copy(explicitTimeout = true))
        running(task.id)
        engine.background(task.id, "owner")
        engine.output(task.id, "owner", waitMillis = 10)
        assertTrue(engine.get(task.id, "owner").active)
        assertEquals(WorkspaceTaskStatus.TIMED_OUT, finished(task.id).status)
        assertTrue(processes.getValue(task.id).closed)
    }

    @Test fun admissionAndOwnershipStayBoundToOriginalConversation() = runBlocking {
        val first = engine.start(task())
        engine.start(task())
        assertTrue(runCatching { engine.start(task()) }.isFailure)
        assertTrue(runCatching { engine.stop(first.id, "different-owner") }.isFailure)
        engine.start(task("other"))
        engine.start(task("other"))
        assertTrue(runCatching { engine.start(task("third")) }.isFailure)
        engine.stopOwner("owner", block = true)
        assertTrue(runCatching { engine.start(task()) }.isFailure)
        engine.unblockOwner("owner")
        engine.start(task())
        assertEquals(3, engine.tasks.value.values.count { it.active })
    }

    @Test fun cancellationDuringSpawnStillClosesTheEventuallyCreatedProcess() = runBlocking {
        val spawnEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fake = FakeProcess()
        val delayedEngine = WorkspaceTaskEngine(scope, directory, spawn = {
            spawnEntered.complete(Unit)
            release.await()
            fake
        }, persist = {})
        val task = delayedEngine.start(task())
        spawnEntered.await()
        val stop = async { delayedEngine.stop(task.id, "owner") }
        delay(30)
        release.complete(Unit)
        assertEquals(WorkspaceTaskStatus.CANCELLED, withTimeout(5_000) { stop.await() }.status)
        assertTrue(fake.closed)
    }

    @Test fun restartMarksActiveInterruptedAndDoesNotRerunOrAutoResume() = runBlocking {
        val task = task(background = true).copy(status = WorkspaceTaskStatus.RUNNING)
        engine.restore(listOf(task))
        val restored = engine.get(task.id, "owner")
        assertEquals(WorkspaceTaskStatus.INTERRUPTED, restored.status)
        assertFalse(restored.resumeAllowed)
        assertEquals(0, spawnCount.get())
        engine.acknowledge(task.id, "owner")
        assertTrue(saved.getValue(task.id).delivered)
    }

    @Test fun pausedOwnerAlsoBlocksAutomaticResumeForCommandsStartingAfterThePause() = runBlocking {
        engine.pauseOwner("owner")
        val task = engine.start(task(background = true))
        assertFalse(task.resumeAllowed)
        engine.resumeOwner("owner")
        assertTrue(engine.get(task.id, "owner").resumeAllowed)
    }

    @Test fun workspaceMutationStopsAndBlocksAdmissionUntilItFinishes() = runBlocking<Unit> {
        val task = engine.start(task(background = true))
        running(task.id)
        engine.blockWorkspace("workspace")
        assertFalse(engine.get(task.id, "owner").active)
        assertTrue(processes.getValue(task.id).closed)
        assertTrue(runCatching { engine.start(task()) }.isFailure)
        engine.unblockWorkspace("workspace")
        engine.start(task())
    }

    @Test fun largeUtf8OutputIsPagedAndLogCapDoesNotKillTask() = runBlocking {
        val output = "你好世界🙂".repeat(20_000)
        val fake = FakeProcess(output)
        val cap = 100_000L
        val capped = WorkspaceTaskEngine(scope, directory, spawn = { fake }, persist = {}, logLimit = cap)
        val task = capped.start(task(background = true))
        withTimeout(5_000) { capped.tasks.first { it[task.id]?.truncated == true } }
        assertTrue(capped.get(task.id, "owner").active)
        assertTrue(capped.get(task.id, "owner").output.length <= 32 * 1024)
        val first = capped.output(task.id, "owner")
        val next = capped.output(task.id, "owner", first.cursor)
        assertTrue(first.cursor in 1..65536)
        assertFalse(first.output.contains('\uFFFD'))
        assertTrue(next.cursor <= cap)
        assertTrue(next.size in (cap - 4)..cap)
        assertFalse(next.output.contains('\uFFFD'))
        capped.stop(task.id, "owner")
        assertTrue(fake.closed)
    }

    private class FakeProcess(text: String = "") : WorkspaceProcess {
        override val input = ByteArrayOutputStream()
        override val stdout = ByteArrayInputStream(text.toByteArray())
        override val stderr = ByteArrayInputStream(byteArrayOf())

        @Volatile var exit: Int? = null

        @Volatile var closed = false
        override fun poll() = exit
        override fun terminate(force: Boolean) {
            exit = 143
        }
        override fun close() {
            closed = true
        }
    }
}
