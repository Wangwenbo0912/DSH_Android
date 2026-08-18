package com.dshbox.app.sandbox

import com.dshbox.app.common.Constants
import com.dshbox.app.common.DeviceProfile
import com.dshbox.app.common.PerformanceTier
import java.io.File

data class SandboxConfig(
    val appFilesDir: File,
    val nativeLibraryDir: String? = null,
    val dshHost: String = Constants.dshHost,
    val dshPort: Int = Constants.dshPort,
    val healthPath: String = "/",
    val maxAutoRestartAttempts: Int = Constants.MAX_AUTO_RESTART_ATTEMPTS,
    val dshReadyTimeoutMs: Long = Constants.dshReadyTimeoutMs,
    val healthCheckTimeoutMs: Long = Constants.healthCheckTimeoutMs,
    val deviceProfile: DeviceProfile? = null,
) {
    val runtimeDir: File = File(appFilesDir, "runtime")
    val sandboxDir: File = File(appFilesDir, "sandbox")
    val userDataDir: File = File(appFilesDir, "user-data")
    val logsDir: File = File(appFilesDir, "logs")
    val backupsDir: File = File(appFilesDir, "backups")
    val updatesDir: File = File(appFilesDir, "updates")

    /** Health check polling interval in ms, adjusted by device performance tier. */
    val healthCheckIntervalMs: Long
        get() = when (deviceProfile?.tier) {
            PerformanceTier.LIGHT -> 5_000L
            PerformanceTier.LIMITED -> 3_000L
            PerformanceTier.UNSUPPORTED -> 10_000L
            else -> 2_000L // HIGH / STANDARD / null
        }

    /** Recommended parallel worker count for extraction/background tasks. */
    val workerCount: Int
        get() = when (deviceProfile?.tier) {
            PerformanceTier.LIGHT -> 1
            PerformanceTier.LIMITED -> 1
            PerformanceTier.UNSUPPORTED -> 1
            else -> 2 // HIGH / STANDARD / null
        }
}
