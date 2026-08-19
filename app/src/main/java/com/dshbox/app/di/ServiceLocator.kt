package com.dshbox.app.di

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.webkit.WebView
import com.dshbox.app.bridge.BridgeRouter
import com.dshbox.app.bridge.SandboxBridgeApi
import com.dshbox.app.common.DeviceProfile
import com.dshbox.app.config.DshConfigWriter
import com.dshbox.app.sandbox.DefaultSandboxManager
import com.dshbox.app.sandbox.SandboxConfig
import com.dshbox.app.sandbox.SandboxProcessRunner
import com.dshbox.app.workspace.WorkspaceManager
import java.security.SecureRandom

/**
 * Creates the MVP graph. On Phase 0 the bridge was stubbed; Phase 1 wires the
 * real [SandboxBridgeApi] with a random capability token so the BridgeRouter
 * can enforce origin verification.
 */
object ServiceLocator {
    fun createAppContainer(context: Context): AppContainer {
        val deviceProfile = detectDeviceProfile(context)
        val sandboxConfig = SandboxConfig(
            appFilesDir = context.filesDir,
            nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
            deviceProfile = deviceProfile,
        )
        val sandboxManager = DefaultSandboxManager(sandboxConfig)

        // Real BridgeApi implementation that executes commands inside the
        // sandbox and maps filesystem operations to app storage.
        val processRunner = SandboxProcessRunner(sandboxConfig)
        val bridgeApi = SandboxBridgeApi(context, sandboxConfig, processRunner)

        // Generate a random 32-hex-char capability token. The WebView JS bridge
        // must present this token for every call to be treated as
        // TRUSTED_DSH_WEBUI. Without it, even localhost calls are downgraded to
        // LOCAL_WEB (no bridge access).
        val dshToken = generateToken()
        val bridgeRouter = BridgeRouter(delegate = bridgeApi, expectedDshToken = dshToken)

        // DSH config writer for GLM-5.2 / DeepSeek-V4-Flash provider settings.
        val dshConfigWriter = DshConfigWriter(sandboxConfig.userDataDir)

        // Persistent workspace picker state.
        val workspaceManager = WorkspaceManager(context)

        return AppContainer(context, sandboxConfig, sandboxManager, bridgeRouter, dshConfigWriter, workspaceManager)
    }

    /**
     * Generate a cryptographically random 32-hex-char token.
     * The token is passed to the WebView's JS bridge handshake; only calls
     * bearing this token are granted TRUSTED_DSH_WEBUI access.
     */
    private fun generateToken(): String {
        val random = SecureRandom()
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Detect device hardware profile for performance tiering.
     */
    private fun detectDeviceProfile(context: Context): DeviceProfile {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        // ActivityManager.getMemoryInfo(outInfo) fills an out-param.
        val memInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        } else {
            null
        }
        val totalRamMb = (memInfo?.totalMem ?: 0L) / (1024L * 1024L)
        val totalRamGb = (totalRamMb / 1024f).coerceAtLeast(2f)

        val storageStats = try {
            val path = context.filesDir.absolutePath
            val stat = StatFs(path)
            stat.restat(path)
            val availableBlocks = stat.availableBlocksLong
            val blockSize = stat.blockSizeLong
            (availableBlocks * blockSize) / (1024L * 1024L * 1024L)
        } catch (_: Exception) {
            0L
        }

        val webViewVersion = try {
            WebView(context).settings.userAgentString
                .substringAfter("AppleWebKit/")
                .substringBefore(" ")
                .take(20)
        } catch (_: Exception) {
            ""
        }

        return DeviceProfile(
            androidApi = Build.VERSION.SDK_INT,
            cpuAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "arm64-v8a",
            cpuCoreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
            totalRamGb = totalRamGb,
            freeStorageGb = storageStats.toFloat(),
            webViewVersion = webViewVersion,
            pageSizeBytes = pageSizeOf(),
        )
    }

    /** Best-effort page size in bytes; 16KiB is the modern arm64 default. */
    private fun pageSizeOf(): Int = when (Build.SUPPORTED_ABIS.firstOrNull()) {
        "arm64-v8a", "x86_64" -> 16_384 // 4KiB on most devices, 16KiB on newer ones; use a safe default
        else -> 4_096
    }
}
