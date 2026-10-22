package me.rerere.workspace

import java.io.InputStream
import java.io.OutputStream

/** One non-interactive command owned independently of a tool's waiter. */
interface WorkspaceProcess : AutoCloseable {
    val input: OutputStream
    val stdout: InputStream
    val stderr: InputStream

    /** null means still running. Only the execution owner polls/reaps. */
    fun poll(): Int?
    fun terminate(force: Boolean)
}
