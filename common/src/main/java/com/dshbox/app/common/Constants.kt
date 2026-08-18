package com.dshbox.app.common

import android.content.Intent

/**
 * App-wide constants with runtime-overridable host/port/timeout values.
 *
 * Override via Intent extras (e.g. adb shell am start ... --es dsh_host "0.0.0.0")
 * or BuildConfig fields. Compile-time defaults are preserved when no override is set.
 */
object Constants {
    // ── Compile-time defaults ────────────────────────────────────────────────
    const val DSH_DEFAULT_HOST = "127.0.0.1"
    const val DSH_DEFAULT_PORT = 3080
    const val DEFAULT_HEALTHCHECK_TIMEOUT_MS = 5_000L
    const val DEFAULT_DSH_READY_TIMEOUT_MS = 120_000L

    // ── Runtime-overridable values ───────────────────────────────────────────
    @Volatile
    var dshHost: String = DSH_DEFAULT_HOST
        private set

    @Volatile
    var dshPort: Int = DSH_DEFAULT_PORT
        private set

    /** WebView loads the DSH loopback URL. Both localhost and 127.0.0.1 are allowed by NSC. */
    val DSH_BASE_URL: String
        get() = "http://$dshHost:$dshPort"

    const val MIN_SUPPORTED_SDK = 29

    /** Default Linux workspace inside the Debian sandbox. */
    const val SANDBOX_WORKSPACE = "/root/projects"

    /** Android-side sandbox directory names (App-specific storage). */
    const val DIR_RUNTIME = "runtime"
    const val DIR_SANDBOX = "sandbox"
    const val DIR_USER_DATA = "user-data"
    const val DIR_LOGS = "logs"
    const val DIR_BACKUPS = "backups"
    const val DIR_UPDATES = "updates"

    const val MAX_AUTO_RESTART_ATTEMPTS = 3

    @Volatile
    var healthCheckTimeoutMs: Long = DEFAULT_HEALTHCHECK_TIMEOUT_MS
        private set

    @Volatile
    var dshReadyTimeoutMs: Long = DEFAULT_DSH_READY_TIMEOUT_MS
        private set

    // ── Intent extra keys ────────────────────────────────────────────────────
    private const val EXTRA_DSH_HOST = "com.dshbox.app.extra.DSH_HOST"
    private const val EXTRA_DSH_PORT = "com.dshbox.app.extra.DSH_PORT"
    private const val EXTRA_HEALTHCHECK_TIMEOUT = "com.dshbox.app.extra.HEALTHCHECK_TIMEOUT"
    private const val EXTRA_DSH_READY_TIMEOUT = "com.dshbox.app.extra.DSH_READY_TIMEOUT"

    /**
     * Apply overrides from an Intent. Call in SandboxService.onCreate() or
     * MainActivity.onCreate() to support adb / CI runtime configuration.
     *
     * Usage:
     *   adb shell am start -n ... \
     *     --es dsh_host "0.0.0.0" \
     *     --ei dsh_port 9090
     */
    fun applyIntentExtras(intent: Intent?) {
        if (intent == null) return
        intent.getStringExtra(EXTRA_DSH_HOST)?.let { dshHost = it }
        intent.getIntExtra(EXTRA_DSH_PORT, dshPort).let {
            if (it in 1..65535) dshPort = it
        }
        intent.getLongExtra(EXTRA_HEALTHCHECK_TIMEOUT, healthCheckTimeoutMs).let {
            if (it > 0) healthCheckTimeoutMs = it
        }
        intent.getLongExtra(EXTRA_DSH_READY_TIMEOUT, dshReadyTimeoutMs).let {
            if (it > 0) dshReadyTimeoutMs = it
        }
    }

    /** Reset all overrides to compile-time defaults (useful in tests). */
    fun resetToDefaults() {
        dshHost = DSH_DEFAULT_HOST
        dshPort = DSH_DEFAULT_PORT
        healthCheckTimeoutMs = DEFAULT_HEALTHCHECK_TIMEOUT_MS
        dshReadyTimeoutMs = DEFAULT_DSH_READY_TIMEOUT_MS
    }
}
