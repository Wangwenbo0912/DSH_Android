package com.dshbox.app.bridge

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.dshbox.app.bridge.api.BridgeApi
import com.dshbox.app.bridge.model.CommandRequest
import com.dshbox.app.bridge.model.CommandResult
import com.dshbox.app.bridge.model.FileContent
import com.dshbox.app.bridge.model.FileEntry
import com.dshbox.app.sandbox.SandboxConfig
import com.dshbox.app.sandbox.SandboxProcessRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Real BridgeApi implementation that maps guest-sandbox operations to the
 * Android host filesystem and PRoot-executed guest commands.
 *
 * Design decisions:
 * - File operations (list/read/write) are mapped to the host filesystem directly
 *   (faster, no guest overhead). Paths inside `/root/projects` are redirected to
 *   [SandboxConfig.userDataDir]; paths inside `/` are redirected to the Debian
 *   rootfs. Path traversal outside these roots is rejected.
 * - Command execution (`execute`) spawns a one-shot PRoot process inside the
 *   sandbox, which is slower but matches the guest environment exactly.
 * - Clipboard/notification use Android platform APIs directly.
 *
 * Thread safety: All public methods are suspend functions that dispatch to
 * [Dispatchers.IO] via [withContext].
 */
class SandboxBridgeApi(
    private val context: Context,
    private val config: SandboxConfig,
    private val processRunner: SandboxProcessRunner,
) : BridgeApi {

    companion object {
        private const val TAG = "SandboxBridgeApi"
        private const val WORKSPACE_PREFIX = "/root/projects"
        private const val DEFAULT_WORKSPACE = WORKSPACE_PREFIX
        private const val EXEC_TIMEOUT_MS = 30_000L
        private const val CHANNEL_ID = "dsh_bridge"
        private const val CHANNEL_NAME = "DSH Bridge"
        private const val NOTIFICATION_ID_BASE = 9001
    }

    private var notificationIdSeq = NOTIFICATION_ID_BASE

    // ── Workspace ─────────────────────────────────────────────────────────────

    private var currentWorkspace: String = DEFAULT_WORKSPACE

    override suspend fun getCurrentWorkspace(): String = currentWorkspace

    override suspend fun setCurrentWorkspace(path: String) {
        currentWorkspace = path
    }

    override suspend fun listWorkspaces(): List<String> = listOf(DEFAULT_WORKSPACE)

    override suspend fun createWorkspace(path: String) {
        val host = guestToHost(path) ?: return
        host.mkdirs()
    }

    // ── Filesystem ────────────────────────────────────────────────────────────

    override suspend fun listDirectory(path: String): List<FileEntry> = withContext(Dispatchers.IO) {
        val host = guestToHost(path)
            ?: return@withContext emptyList<FileEntry>()
        if (!host.isDirectory) return@withContext emptyList<FileEntry>()
        host.listFiles()
            ?.sortedBy { it.name.lowercase() }
            ?.map { fileToEntry(it) }
            ?: emptyList<FileEntry>()
    }

    override suspend fun stat(path: String): FileEntry = withContext(Dispatchers.IO) {
        val host = guestToHost(path) ?: return@withContext FileEntry(path, path, true, null, null)
        fileToEntry(host)
    }

    override suspend fun readText(path: String): FileContent = withContext(Dispatchers.IO) {
        val host = guestToHost(path) ?: return@withContext FileContent(path, "")
        if (!host.isFile) return@withContext FileContent(path, "")
        FileContent(
            path = path,
            content = try { host.readText() } catch (_: IOException) { "" },
        )
    }

    override suspend fun writeText(path: String, content: String) = withContext(Dispatchers.IO) {
        val host = guestToHost(path) ?: return@withContext
        host.parentFile?.mkdirs()
        try {
            host.writeText(content)
        } catch (e: IOException) {
            Log.w(TAG, "writeText($path) failed: ${e.message}")
        }
    }

    override suspend fun createDirectory(path: String) = withContext(Dispatchers.IO) {
        val host = guestToHost(path) ?: return@withContext
        host.mkdirs()
    }

    override suspend fun delete(path: String) = withContext(Dispatchers.IO) {
        val host = guestToHost(path) ?: return@withContext
        if (host.isDirectory) host.deleteRecursively() else host.delete()
    }

    override suspend fun move(from: String, to: String) = withContext(Dispatchers.IO) {
        val src = guestToHost(from) ?: return@withContext
        val dst = guestToHost(to) ?: return@withContext
        dst.parentFile?.mkdirs()
        src.renameTo(dst)
    }

    override suspend fun copy(from: String, to: String) = withContext(Dispatchers.IO) {
        val src = guestToHost(from) ?: return@withContext
        val dst = guestToHost(to) ?: return@withContext
        dst.parentFile?.mkdirs()
        src.copyTo(dst, overwrite = true)
    }

    // ── Command execution ─────────────────────────────────────────────────────

    override suspend fun execute(request: CommandRequest): CommandResult = withContext(Dispatchers.IO) {
        val cmd = request.command.trim()
        if (cmd.isEmpty()) {
            return@withContext CommandResult(null, "", "empty command", timedOut = false)
        }
        val fullCmd = if (request.args.isNotEmpty()) {
            cmd + " " + request.args.joinToString(" ") { shellEscape(it) }
        } else {
            cmd
        }
        val cwd = request.cwd ?: currentWorkspace
        val timeoutMs = request.timeoutMs ?: EXEC_TIMEOUT_MS

        // Build a one-shot PRoot command for guest execution.
        val prootBinary = findProotBinary()
        val rootfsDir = findRootfsDir()
        val workspaceBind = config.userDataDir.absolutePath
        val prootCmd = processRunner.buildProotExecCommand(
            prootBinary = prootBinary,
            rootfsDir = rootfsDir.absolutePath,
            workspaceBind = workspaceBind,
            guestCommand = fullCmd,
            guestCwd = cwd,
        )

        try {
            val pb = ProcessBuilder(prootCmd)
            pb.redirectErrorStream(false)
            val prootEnv = prootEnv()
            pb.environment().putAll(prootEnv)

            val process = pb.start()
            val pid = getProcessPid(process)

            // Read stdout and stderr concurrently to avoid deadlock.
            val stdoutBuilder = StringBuilder()
            val stderrBuilder = StringBuilder()
            val stdoutThread = Thread({
                try {
                    process.inputStream.bufferedReader().forEachLine { stdoutBuilder.appendLine(it) }
                } catch (_: IOException) { /* stream closed */ }
            }, "bridge-stdout-$pid").also { it.isDaemon = true; it.start() }
            val stderrThread = Thread({
                try {
                    process.errorStream.bufferedReader().forEachLine { stderrBuilder.appendLine(it) }
                } catch (_: IOException) { /* stream closed */ }
            }, "bridge-stderr-$pid").also { it.isDaemon = true; it.start() }

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            // Wait for reader threads to finish.
            stdoutThread.join(1_000)
            stderrThread.join(1_000)

            if (finished) {
                CommandResult(
                    exitCode = process.exitValue(),
                    stdout = stdoutBuilder.toString().trimEnd('\n'),
                    stderr = stderrBuilder.toString().trimEnd('\n'),
                    timedOut = false,
                    processId = pid,
                )
            } else {
                process.destroyForcibly()
                process.waitFor(2_000L, TimeUnit.MILLISECONDS)
                CommandResult(
                    exitCode = null,
                    stdout = stdoutBuilder.toString().trimEnd('\n'),
                    stderr = stderrBuilder.toString().trimEnd('\n') + "\n[timed out after ${timeoutMs}ms]",
                    timedOut = true,
                    processId = pid,
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "execute failed: ${e.message}", e)
            CommandResult(null, "", "execution failed: ${e.message}", timedOut = false)
        }
    }

    override suspend fun cancel(processId: Long) = withContext(Dispatchers.IO) {
        // Process already completed or was destroyed on timeout; no-op.
        Log.i(TAG, "cancel($processId): one-shot process, no cancellation needed")
        Unit
    }

    override suspend fun listProcesses(): List<Long> = withContext(Dispatchers.IO) {
        val myPid = android.os.Process.myPid()
        val table = readProcTable() ?: return@withContext emptyList()
        // BFS from myPid: collect all descendant PIDs = sandbox process tree.
        val result = mutableListOf<Long>()
        val queue = ArrayDeque<Int>()
        queue.addAll(childrenOf(table, myPid))
        while (queue.isNotEmpty()) {
            val pid = queue.removeFirst()
            result.add(pid.toLong())
            queue.addAll(childrenOf(table, pid))
        }
        result.sorted()
    }

    override suspend fun killProcess(processId: Long) = withContext(Dispatchers.IO) {
        if (processId <= 0L) {
            Log.w(TAG, "killProcess($processId): invalid PID, refusing to execute kill")
            return@withContext
        }
        // Safety: only allow killing PIDs inside the sandbox process tree.
        val myPid = android.os.Process.myPid()
        if (!isDescendantOf(processId.toInt(), myPid)) {
            Log.w(TAG, "killProcess($processId): not a sandbox process, refusing")
            return@withContext
        }
        try {
            ProcessBuilder("/system/bin/kill", "-KILL", processId.toString()).start().waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "killProcess($processId) failed: ${t.message}")
        }
        Unit
    }

    // ── Android platform ──────────────────────────────────────────────────────

    override suspend fun showNotification(title: String, body: String) = withContext(Dispatchers.IO) {
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "showNotification: POST_NOTIFICATIONS not granted, skipping")
            return@withContext
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(notificationIdSeq++, notification)
    }

    override suspend fun clipboardRead(): String = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    }

    override suspend fun clipboardWrite(text: String) = withContext(Dispatchers.Main) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("DSH Bridge", text))
        Unit
    }

    // ── Path mapping ──────────────────────────────────────────────────────────

    /**
     * Converts a guest sandbox path to a host Android [File].
     *
     * - `/root/projects/...` → [SandboxConfig.userDataDir]/...
     * - `/root/projects` → [SandboxConfig.userDataDir]
     * - `/` or empty → Debian rootfs
     * - `/anything/...` → Debian rootfs/anything/...
     * - null → invalid path (outside allowed roots or traversal attack)
     */
    private fun guestToHost(guestPath: String): File? {
        val normalized = guestPath.trimStart('/').trimEnd('/')
        val hostRoot = when {
            normalized == "root/projects" || normalized.startsWith("root/projects/") -> {
                val rel = normalized.removePrefix("root/projects").trimStart('/')
                if (rel.isEmpty()) config.userDataDir else File(config.userDataDir, rel)
            }
            normalized.isEmpty() -> findRootfsDir()
            else -> File(findRootfsDir(), normalized)
        }
        // Path traversal guard: the resolved path must be inside the root.
        val resolved = try { hostRoot.canonicalFile } catch (_: Exception) { return null }
        val allowedRoots = setOf(
            config.userDataDir.canonicalPath,
            findRootfsDir().canonicalPath,
        )
        return if (allowedRoots.any { resolved.path.startsWith(it) }) resolved else null
    }

    private fun findRootfsDir(): File = File(config.runtimeDir, "runtime-current/debian")

    private fun findProotBinary(): String {
        val bundled = config.nativeLibraryDir?.let { File(it, "libproot.so") }
        if (bundled?.isFile == true) return bundled.absolutePath
        return File(config.runtimeDir, "runtime-current/android-side/bin/proot").absolutePath
    }

    private fun prootEnv(): Map<String, String> {
        val runtimeDir = File(config.runtimeDir, "runtime-current")
        val libDir = config.nativeLibraryDir?.let { File(it) }
            ?.takeIf { File(it, "libandroid-shmem.so").isFile }
            ?: File(runtimeDir, "android-side/lib")
        val loader = config.nativeLibraryDir?.let { File(it, "libproot-loader.so") }
            ?.takeIf { it.isFile }
            ?: File(runtimeDir, "android-side/libexec/proot/loader")
        val tmpDir = File(runtimeDir, "tmp").also { it.mkdirs() }
        return mapOf(
            "LD_LIBRARY_PATH" to libDir.absolutePath,
            "PROOT_TMP_DIR" to tmpDir.absolutePath,
            "PROOT_LOADER" to loader.absolutePath,
            "PATH" to "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
        )
    }

    private fun fileToEntry(file: File): FileEntry = FileEntry(
        name = file.name,
        path = file.absolutePath,
        isDirectory = file.isDirectory,
        sizeBytes = if (file.isFile) file.length() else null,
        modifiedAtMs = file.lastModified().takeIf { it > 0L },
    )

    /** Shell-escapes a string for safe inclusion in a bash -c command. */
    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"

    /**
     * Returns the OS PID of a running [Process]. Tries the reflectively-accessible
     * [getPid] method first (available on most Android API 30+ builds), then
     * falls back to reading the internal `pid` field. Returns -1 on failure.
     */
    private fun getProcessPid(process: Process): Long {
        return try {
            val m = process.javaClass.getMethod("getPid")
            (m.invoke(process) as? Int)?.toLong() ?: -1L
        } catch (_: NoSuchMethodException) {
            try {
                val pidField = process.javaClass.getDeclaredField("pid")
                pidField.isAccessible = true
                pidField.getLong(process)
            } catch (_: Exception) {
                -1L
            }
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    /** Creates the "dsh_bridge" notification channel (no-op after the first call). */
    private fun createNotificationChannel() {
        // Channel creation is idempotent; guard against re-creation on API < 26 anyway.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Notifications requested from the DSH WebUI"
        }
        manager.createNotificationChannel(channel)
    }

    // ── Process table helpers ─────────────────────────────────────────────────

    /** Reads /proc into a pid → ppid map. Returns null when /proc is unreadable. */
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

    /** Direct children of [parent] from a pid → ppid table. */
    private fun childrenOf(all: Map<Int, Int>, parent: Int): List<Int> =
        all.filterValues { it == parent }.keys.sorted()

    private fun readTextOrNull(file: File): String? =
        try { file.readText() } catch (_: Throwable) { null }

    /** True when [pid] is [rootPid] itself or a descendant of [rootPid]. */
    private fun isDescendantOf(pid: Int, rootPid: Int): Boolean {
        val table = readProcTable() ?: return false
        if (pid == rootPid) return true
        var cur: Int? = pid
        var hops = 0
        while (cur != null && hops < 64) {
            if (cur == rootPid) return true
            cur = table[cur]
            hops++
        }
        return false
    }
}