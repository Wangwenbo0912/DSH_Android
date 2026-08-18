package com.dshbox.app.bridge

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
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
    }

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

    override suspend fun listProcesses(): List<Long> = emptyList()

    override suspend fun killProcess(processId: Long) = withContext(Dispatchers.IO) {
        try {
            ProcessBuilder("/system/bin/kill", "-KILL", processId.toString()).start().waitFor()
        } catch (t: Throwable) {
            Log.w(TAG, "killProcess($processId) failed: ${t.message}")
        }
        Unit
    }

    // ── Android platform ──────────────────────────────────────────────────────

    override suspend fun showNotification(title: String, body: String) {
        Log.i(TAG, "showNotification: $title — $body")
        // TODO(phase-2): call NotificationManager directly when the bridge gains
        // a reference to the app's notification channel.
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
     * Returns the OS PID of a running [Process]. Uses Java 9+ [Process.pid]
     * when available (API 30+); falls back to reflection on API 29.
     */
    private fun getProcessPid(process: Process): Long {
        // Android's java.lang.Process does not have pid() from Java 9+;
        // use reflection to access the internal pid field.
        return try {
            val pidField = process.javaClass.getDeclaredField("pid")
            pidField.isAccessible = true
            pidField.getLong(process)
        } catch (_: Exception) {
            try {
                val m = process.javaClass.getMethod("getPid")
                (m.invoke(process) as? Int)?.toLong() ?: -1L
            } catch (_: Exception) {
                -1L
            }
        }
    }
}