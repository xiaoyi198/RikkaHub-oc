package me.rerere.workspace

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Device coverage for the JNI pipe/session boundary, separate from the scheduler's JVM tests. */
@RunWith(AndroidJUnit4::class)
class NativeWorkspaceProcessTest {
    private fun process(command: String) = NativeWorkspaceProcess(
        listOf("/system/bin/sh", "-c", command),
        InstrumentationRegistry.getInstrumentation().targetContext.filesDir,
        System.getenv(),
    )

    @Test fun exitIsObservedWithoutReapingAndStdinIsNonInteractive() {
        process("cat; printf final-output; exit 7").use { process ->
            process.input.close()
            assertEquals("final-output", process.stdout.bufferedReader().readText())
            val deadline = System.currentTimeMillis() + 3_000
            while (process.poll() == null && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertEquals(7, process.poll())
            assertEquals(7, process.poll())
        }
    }

    @Test fun stoppingKillsAChildThatIgnoresTerm() {
        process("sh -c 'trap \"\" TERM; echo ready; while :; do sleep 1; done' & echo \$!; wait").use { process ->
            process.input.close()
            val output = process.stdout.bufferedReader()
            val child = output.readLine().toInt()
            assertEquals("ready", output.readLine())
            process.terminate(false)
            Thread.sleep(100)
            process.poll() // Observing the tracer's exit must not lose ownership of surviving children.
            process.terminate(true)
            val deadline = System.currentTimeMillis() + 3_000
            while (isLive(child) && System.currentTimeMillis() < deadline) Thread.sleep(20)
            assertFalse(isLive(child))
        }
    }

    private fun isLive(pid: Int): Boolean = runCatching {
        File("/proc/$pid/stat").readText().substringAfterLast(") ").first() != 'Z'
    }.getOrDefault(false)
}
