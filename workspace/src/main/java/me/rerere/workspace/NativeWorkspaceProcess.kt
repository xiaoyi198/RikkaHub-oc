package me.rerere.workspace

import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import java.io.File

/** Pipe-based counterpart of terminal's termux_pty.cpp; each command owns a new session. */
internal class NativeWorkspaceProcess(command: List<String>, cwd: File, env: Map<String, String>) : WorkspaceProcess {
    private val handles = NativeWorkspaceProcessJni.start(
        command.toTypedArray(),
        cwd.absolutePath,
        env.map { "${it.key}=${it.value}" }.toTypedArray()
    )
    private val pid = handles[0]
    private val inputFd = ParcelFileDescriptor.adoptFd(handles[1])
    private val stdoutFd = ParcelFileDescriptor.adoptFd(handles[2])
    private val stderrFd = ParcelFileDescriptor.adoptFd(handles[3])
    override val input = ParcelFileDescriptor.AutoCloseOutputStream(inputFd)
    override val stdout = ParcelFileDescriptor.AutoCloseInputStream(stdoutFd)
    override val stderr = ParcelFileDescriptor.AutoCloseInputStream(stderrFd)

    @Volatile private var exit: Int? = null
    private var closed = false
    private val tracees = mutableMapOf<Int, String>()

    @Synchronized
    override fun poll(): Int? {
        exit?.let { return it }
        return NativeWorkspaceProcessJni.poll(pid).takeUnless { it == Int.MIN_VALUE }?.also { exit = it }
    }

    @Synchronized
    override fun terminate(force: Boolean) {
        if (closed) return // poll() leaves the zombie reserved until close() reaps it.
        val signal = if (force) OsConstants.SIGKILL else OsConstants.SIGTERM
        // Ported from LocalPtyTransport.sendKill: PRoot tracees can occupy other process groups.
        // Snapshot session members before killing the tracer, while their ownership is observable.
        repeat(if (force) 3 else 1) {
            val members = File("/proc").list().orEmpty().mapNotNull { name ->
                val member = name.toIntOrNull() ?: return@mapNotNull null
                val stat = runCatching { File("/proc/$name/stat").readText() }.getOrNull() ?: return@mapNotNull null
                val fields = stat.substringAfterLast(") ").split(' ')
                val startTime = fields.getOrNull(19) ?: return@mapNotNull null
                val traced = runCatching {
                    File("/proc/$name/status").useLines { lines ->
                        lines.firstOrNull { it.startsWith("TracerPid:") }?.substringAfter(':')?.trim()?.toIntOrNull() ==
                            pid
                    }
                }.getOrDefault(false)
                val owned = fields.getOrNull(3)?.toIntOrNull() == pid || traced || tracees[member] == startTime
                if (owned) tracees[member] = startTime
                member.takeIf { owned && fields.firstOrNull() != "Z" }
            }
            members.filter { it != pid }.forEach { runCatching { Os.kill(it, signal) } }
            runCatching { Os.kill(-pid, signal) }
            runCatching { Os.kill(pid, signal) }
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        terminate(true)
        closed = true
        listOf(inputFd, stdoutFd, stderrFd).forEach { runCatching { it.close() } }
        // Reap on a daemon waiter if the bounded owner wait ended before the kernel delivered SIGKILL.
        Thread({ NativeWorkspaceProcessJni.reap(pid) }, "workspace-reaper").apply {
            isDaemon = true
            start()
        }
    }
}

@Keep
internal object NativeWorkspaceProcessJni {
    init {
        System.loadLibrary("workspace")
    }
    external fun start(command: Array<String>, cwd: String, environment: Array<String>): IntArray
    external fun poll(pid: Int): Int
    external fun reap(pid: Int)
}
