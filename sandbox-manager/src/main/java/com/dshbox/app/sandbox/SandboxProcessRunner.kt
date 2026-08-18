package com.dshbox.app.sandbox

import com.dshbox.app.common.LogRedactor
import java.io.File
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/**
 * Thin wrapper around process execution. On Android, long-running PRoot/DSH
 * processes must be owned by a Foreground Service (see lifecycle spec), never
 * by an Activity.
 */
class SandboxProcessRunner(
    private val config: SandboxConfig,
) {

    data class RunningProcess(
        val process: Process,
        val tag: String,
    )

    fun logsDir(): File = config.logsDir.also { it.mkdirs() }

    companion object {
        private const val TAG = "SandboxProcessRunner"
        private const val MAX_LOG_BYTES = 5L * 1024 * 1024 // 5 MB
        private const val GRACE_MS = 3_000L // graceful shutdown timeout
        private const val POLL_INTERVAL_MS = 100L
    }

    fun redact(line: String): String = LogRedactor.redact(line)

    /**
     * Builds the PRoot start command. Paths are absolute app-specific storage
     * paths; the Debian side still sees a normal Linux file system.
     */
    fun buildProotStartCommand(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
    ): List<String> = listOf(
        prootBinary,
        "--rootfs=$rootfsDir",
        "--bind=/system",
        "--bind=/apex",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=$workspaceBind:/root/projects",
        "--cwd=/root",
        // When proot is destroyed (sandbox stop), kill the whole guest tree
        // (bash/node/DSH) instead of leaving orphaned processes behind.
        "--kill-on-exit",
        // The initial command is a host binary (/system/bin/sh) because
        // untrusted_app cannot exec guest ELFs from app data. Once the host
        // shell execs /bin/bash inside the guest rootfs, PRoot's loader takes
        // over and loads the guest ELF in memory, bypassing the kernel exec
        // restriction.
        "/system/bin/sh", "-c",
        "exec /usr/bin/bash /opt/dshapp/start_dsh.sh",
    )

    /**
     * Builds a one-shot PRoot command for executing a command inside the
     * sandbox. Used by the BridgeApi to run guest commands without an
     * interactive shell session.
     */
    fun buildProotExecCommand(
        prootBinary: String,
        rootfsDir: String,
        workspaceBind: String,
        guestCommand: String,
        guestCwd: String = "/root/projects",
    ): List<String> = listOf(
        prootBinary,
        "--rootfs=$rootfsDir",
        "--bind=/system",
        "--bind=/apex",
        "--bind=/proc",
        "--bind=/dev",
        "--bind=$workspaceBind:/root/projects",
        "--cwd=$guestCwd",
        "--kill-on-exit",
        "/system/bin/sh", "-c",
        "exec /usr/bin/bash -c '${guestCommand.replace("'", "'\\''")}'",
    )

    fun start(
        command: List<String>,
        tag: String,
        workingDir: File? = null,
        env: Map<String, String> = emptyMap(),
    ): RunningProcess {
        val pb = ProcessBuilder(command)
        workingDir?.let { pb.directory(it) }
        pb.environment().putAll(env)
        pb.redirectErrorStream(true)

        val logFile = File(logsDir(), "process-$tag.log")
        val process = pb.start()
        val running = RunningProcess(process, tag)

        val input: InputStream = process.inputStream
        val thread = Thread({
            try {
                input.bufferedReader(StandardCharsets.UTF_8).forEachLine { line ->
                    appendToLog(logFile, redact(line))
                }
            } catch (_: IOException) {
                // stream closed by process exit
            }
        }, "sandbox-log-$tag")
        thread.isDaemon = true
        thread.start()

        return running
    }

    // ── Log rotation ──────────────────────────────────────────────────────────

    /**
     * Appends one line to the log file, rotating when the file exceeds
     * [MAX_LOG_BYTES]. Keeps at most one rotated backup (.1).
     */
    private fun appendToLog(logFile: File, line: String) {
        if (logFile.isFile && logFile.length() > MAX_LOG_BYTES) {
            rotateLog(logFile)
        }
        logFile.appendText(line + "\n")
    }

    private fun rotateLog(logFile: File) {
        try {
            val backup = File(logFile.parentFile, logFile.name + ".1")
            if (backup.exists()) backup.delete()
            logFile.renameTo(backup)
        } catch (t: Throwable) {
            Log.w(TAG, "log rotation failed: ${t.message}")
        }
    }

    // ── Graceful stop (SIGTERM → SIGKILL) ─────────────────────────────────────

    /**
     * Stops the sandbox with a two-phase graceful shutdown:
     * 1. SIGTERM to every process in the PRoot tree (children first), giving
     *    them a chance to save state.
     * 2. Wait up to [GRACE_MS] for all processes to exit.
     * 3. SIGKILL survivors.
     *
     * Without this, Process.destroy() alone may fail to deliver the signal to
     * PRoot, and even when PRoot dies its guest processes are reparented and
     * keep running.
     */
    fun stop(running: RunningProcess) {
        Log.i(TAG, "stop(): stopping ${running.tag}")
        val myPid = android.os.Process.myPid()
        val prootPid = findDescendantByCmdline(myPid, "libproot.so")
        if (prootPid != null) {
            val tree = descendantPids(prootPid)
            Log.i(TAG, "stop(): proot=$prootPid descendants=${tree.size}")
            // Phase 1: graceful SIGTERM to every process (children first).
            (tree + prootPid).forEach { signalPid(it, "TERM") }
            // Phase 2: wait for graceful exit, then force-kill survivors.
            if (!waitForExit(tree + prootPid, GRACE_MS)) {
                Log.w(TAG, "stop(): processes did not exit gracefully; force-killing")
                (tree + prootPid).forEach { signalPid(it, "KILL") }
            }
        } else {
            Log.w(TAG, "stop(): proot process not found under pid $myPid")
        }
        // Best-effort cleanup of the Process object itself.
        try {
            running.process.destroy()
        } catch (_: Throwable) {
        }
    }

    /**
     * Stops only the DSH guest process tree (node bin.js + children), leaving
     * the PRoot/sandbox alive. This is a lighter alternative to [stop] for
     * DSH_RESTART recovery.
     */
    fun stopDsh() {
        val myPid = android.os.Process.myPid()
        // Find the DSH guest process by cmdline patterns. After start_dsh.sh
        // exec's into node, the process argv contains "@deepseek-ai/dsh" or
        // "bin.js" or "dsh web".
        val dshNode = findDescendantByCmdline(myPid, "@deepseek-ai/dsh")
            ?: findDescendantByCmdline(myPid, "bin.js")
            ?: findDescendantByCmdline(myPid, "start_dsh.sh")
        if (dshNode != null) {
            val tree = descendantPids(dshNode)
            Log.i(TAG, "stopDsh(): dsh=$dshNode descendants=${tree.size}")
            // Phase 1: graceful SIGTERM (children first so DSH can save state).
            (tree + dshNode).forEach { signalPid(it, "TERM") }
            // Phase 2: wait, then force-kill.
            if (!waitForExit(tree + dshNode, GRACE_MS)) {
                Log.w(TAG, "stopDsh(): DSH did not exit gracefully; force-killing")
                (tree + dshNode).forEach { signalPid(it, "KILL") }
            }
        } else {
            Log.w(TAG, "stopDsh(): DSH process not found under pid $myPid")
        }
    }

    // ── Process tree helpers ──────────────────────────────────────────────────

    /** Returns pids of all processes whose cmdline contains [needle] and whose ancestor is [rootPid]. */
    private fun findDescendantByCmdline(rootPid: Int, needle: String): Int? {
        val all = readProcTable() ?: return null
        val children = childrenOf(all, rootPid)
        val queue = ArrayDeque(children)
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            val cmdline = readCmdline(pid)
            if (cmdline != null && cmdline.contains(needle)) return pid
            queue.addAll(childrenOf(all, pid))
        }
        return null
    }

    /** All descendant pids of [rootPid], BFS order (parents before children). */
    private fun descendantPids(rootPid: Int): List<Int> {
        val all = readProcTable() ?: return emptyList()
        val result = mutableListOf<Int>()
        val queue = ArrayDeque(childrenOf(all, rootPid))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            result.add(pid)
            queue.addAll(childrenOf(all, pid))
        }
        return result
    }

    private fun readProcTable(): Map<Int, Int>? {
        val dir = File("/proc")
        val entries = dir.listFiles { f -> f.name.all { it.isDigit() } } ?: return null
        val map = HashMap<Int, Int>()
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val stat = readTextOrNull(File(entry, "stat")) ?: continue
            // Format: pid (comm) state ppid ...
            val rest = stat.substringAfter(") ").trim()
            val ppid = rest.split(' ').getOrNull(1)?.toIntOrNull() ?: continue
            map[pid] = ppid
        }
        return map
    }

    private fun childrenOf(all: Map<Int, Int>, parent: Int): List<Int> =
        all.filterValues { it == parent }.keys.sorted()

    private fun readCmdline(pid: Int): String? =
        readTextOrNull(File("/proc/$pid/cmdline"))?.replace('\u0000', ' ')

    private fun readTextOrNull(file: File): String? =
        try {
            file.readText()
        } catch (_: Throwable) {
            null
        }

    /**
     * Wait for all pids to exit, polling /proc. Returns true if all exited
     * within the timeout; false if any remain.
     */
    private fun waitForExit(pids: List<Int>, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        var remaining = pids.toSet()
        while (System.currentTimeMillis() < deadline && remaining.isNotEmpty()) {
            remaining = remaining.filter { pidExists(it) }.toSet()
            if (remaining.isEmpty()) return true
            try { Thread.sleep(POLL_INTERVAL_MS) } catch (_: InterruptedException) { break }
        }
        return remaining.isEmpty()
    }

    private fun pidExists(pid: Int): Boolean = File("/proc/$pid").isDirectory

    private fun signalPid(pid: Int, signal: String) {
        try {
            val exit = ProcessBuilder("/system/bin/kill", "-$signal", pid.toString()).start().waitFor()
            if (exit != 0) Log.w(TAG, "kill -$signal $pid failed exit=$exit")
        } catch (t: Throwable) {
            Log.w(TAG, "kill -$signal $pid threw: ${t.message}")
        }
    }

    /**
     * Legacy alias — kept for backward compatibility.
     * @see signalPid
     */
    private fun killPid(pid: Int) = signalPid(pid, "KILL")

    fun killAll(prefix: String) {
        val dir = File("/proc")
        val entries = dir.listFiles { f -> f.name.all { it.isDigit() } } ?: return
        for (entry in entries) {
            val pid = entry.name.toIntOrNull() ?: continue
            val cmdline = readCmdline(pid) ?: continue
            if (cmdline.contains(prefix)) {
                Log.w(TAG, "killAll: killing $pid (cmdline contains '$prefix')")
                signalPid(pid, "KILL")
            }
        }
    }
}
