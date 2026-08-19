package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError

/**
 * Monitors DSH health samples and implements the bounded retry policy used
 * by [DefaultSandboxManager]'s health loop: minimal-destruction first,
 * limited automatic retries, error escalation after repeated failures.
 *
 * The supervisor is stateless between health samples except for the
 * consecutive-failure counter; the health loop owns the actual recovery
 * actions (restart / recover escalation) and resets the supervisor whenever
 * a new loop starts.
 */
class SandboxSupervisor(
    private val config: SandboxConfig,
) {
    var consecutiveFailures: Int = 0
        private set

    /** Reset the failure counter (called when a loop starts or DSH is healthy). */
    fun reset() {
        consecutiveFailures = 0
    }

    /**
     * Apply the retry policy to one health sample:
     * - a [SandboxHealth.webUiReady] sample resets the failure counter;
     * - consecutive failures are counted, and once the configured cap is
     *   reached the sample is marked [SandboxState.ERROR] so the health loop
     *   escalates instead of retrying forever.
     */
    fun supervise(health: SandboxHealth): SandboxHealth {
        if (health.webUiReady) {
            consecutiveFailures = 0
            return health
        }
        consecutiveFailures++
        if (consecutiveFailures >= config.maxAutoRestartAttempts) {
            return health.copy(
                sandboxState = SandboxState.ERROR,
                lastError = "max auto-restart attempts reached ($consecutiveFailures/$config.maxAutoRestartAttempts)",
            )
        }
        return health
    }
}

fun interface SandboxHealthChecker {
    suspend fun check(): SandboxHealth
}

fun recoveryError(level: RecoveryLevel, message: String): AppError =
    AppError("RECOVERY_${level.name}", message, recoverable = true)