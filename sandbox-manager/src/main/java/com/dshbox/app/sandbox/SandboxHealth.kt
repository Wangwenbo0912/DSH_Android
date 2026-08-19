package com.dshbox.app.sandbox

data class SandboxHealth(
    val sandboxState: SandboxState,
    val dshProcessRunning: Boolean,
    val portOpen: Boolean,
    val webUiReady: Boolean,
    val lastError: String? = null,
    val dshVersion: String? = null,
    val pluginApiVersion: String? = null,
)

data class DshRuntimeStatus(
    val dshVersion: String?,
    val pluginApiVersion: String?,
    val baseUrl: String,
    val ready: Boolean,
)
