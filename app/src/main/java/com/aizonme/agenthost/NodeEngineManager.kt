package com.aizonme.agenthost

import android.content.Context
import android.system.Os
import android.util.Log
import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Hosts a single embedded Node.js runtime (nodejs-mobile) for the lifetime of
 * the process.
 *
 * The runtime is booted on a dedicated background thread because
 * [startNodeWithArguments] blocks on the libuv event loop until Node exits.
 * Commands are pushed to Node over its stdin; Node's stdout/stderr are streamed
 * back to a [NodeOutputCallback].
 */
class NodeEngineManager(context: Context) {

    /** Receives output streamed back from the embedded Node.js runtime. */
    interface NodeOutputCallback {
        /** A line written to Node's stdout. */
        fun onStdout(line: String)

        /** A line written to Node's stderr. */
        fun onStderr(line: String)
    }

    companion object {
        private const val TAG = "NodeEngineManager"

        init {
            // libnode must be resolved before our bridge, which references it.
            System.loadLibrary("node")
            System.loadLibrary("native-lib")
        }
    }

    // Captured eagerly so we never retain the Context.
    private val homePath: String = context.filesDir.absolutePath

    private val started = AtomicBoolean(false)

    @Volatile
    private var callback: NodeOutputCallback? = null

    // Write end of the pipe wired to Node's stdin; commands are pushed here.
    @Volatile
    private var stdinWriter: OutputStreamWriter? = null

    /**
     * JNI bridge into libnode. Blocks on the Node event loop and returns the
     * process exit code, so it must be called from a background thread.
     */
    private external fun startNodeWithArguments(arguments: Array<String>): Int

    /**
     * Boots the Node.js runtime on a background thread.
     *
     * @param scriptPath absolute path to the entry script Node should run
     *        (typically extracted from assets into [Context.getFilesDir]).
     * @param callback receives stdout/stderr lines emitted by the runtime.
     */
    fun start(scriptPath: String, callback: NodeOutputCallback) {
        if (!started.compareAndSet(false, true)) {
            Log.w(TAG, "Node runtime already started; ignoring start()")
            return
        }
        this.callback = callback

        Thread({
            try {
                configureEnvironment()
                redirectStdio()
                // Blocks here until the Node event loop drains / Node exits.
                val exitCode = startNodeWithArguments(arrayOf("node", scriptPath))
                Log.i(TAG, "Node runtime exited with code $exitCode")
            } catch (t: Throwable) {
                Log.e(TAG, "Node runtime terminated abnormally", t)
            } finally {
                started.set(false)
            }
        }, "nodejs-mobile").start()
    }

    /**
     * Sends [command] to the running Node process by writing a single line to
     * its stdin. The Node entry script is expected to read commands from
     * `process.stdin`. No-ops (with a warning) if the runtime is not yet ready.
     */
    fun sendCommandToNode(command: String) {
        val writer = stdinWriter
        if (writer == null) {
            Log.w(TAG, "sendCommandToNode called before the runtime was ready")
            return
        }
        try {
            synchronized(writer) {
                writer.write(command)
                writer.write("\n")
                writer.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send command to Node", e)
        }
    }

    /**
     * Writes the environment the embedded runtime inherits. Uses libc `setenv`
     * (via [Os]) so the values surface in Node as `process.env.*`. Must run
     * before `node::Start`.
     *
     * - HOME points at app-private storage so Node and any CLI tooling it
     *   spawns have a writable home directory.
     * - SHELL points at Android's system shell for child-process spawning.
     */
    private fun configureEnvironment() {
        Os.setenv("HOME", homePath, true)
        Os.setenv("SHELL", "/system/bin/sh", true)
    }

    /**
     * Rewires the process stdio file descriptors before Node starts:
     * fd 0 (stdin) reads from a pipe we feed via [sendCommandToNode], while
     * fd 1/2 (stdout/stderr) are drained by reader threads into the callback.
     */
    private fun redirectStdio() {
        // stdin: app -> Node. dup2 the pipe's read end onto fd 0; keep the
        // write end to push commands into the runtime.
        val stdinPipe = Os.pipe() // [read, write]
        Os.dup2(stdinPipe[0], 0)
        stdinWriter = OutputStreamWriter(FileOutputStream(stdinPipe[1]))

        // stdout / stderr: Node -> app. dup2 the pipe write ends onto fd 1/2,
        // then forward each line read from the corresponding read end.
        val stdoutPipe = Os.pipe()
        Os.dup2(stdoutPipe[1], 1)
        pumpOutput(stdoutPipe[0], isError = false)

        val stderrPipe = Os.pipe()
        Os.dup2(stderrPipe[1], 2)
        pumpOutput(stderrPipe[0], isError = true)
    }

    /** Reads [source] line by line on its own thread, forwarding to the callback. */
    private fun pumpOutput(source: FileDescriptor, isError: Boolean) {
        Thread({
            try {
                BufferedReader(InputStreamReader(FileInputStream(source))).use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        val cb = callback
                        if (cb != null) {
                            if (isError) cb.onStderr(line) else cb.onStdout(line)
                        }
                        line = reader.readLine()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Output reader stopped", e)
            }
        }, if (isError) "node-stderr" else "node-stdout").start()
    }
}
