package com.dshbox.app.sandbox

import com.dshbox.app.common.AppError
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * HTTP health checker for the local DSH WebUI. A Ready decision requires
 * the port to be open and an HTTP probe to return any HTTP response (not
 * necessarily 200; DSH is an SPA, so 200/302/404 on a local route may still
 * mean the webserver is alive).
 */
class HttpHealthChecker(
    private val host: String = Constants.DSH_DEFAULT_HOST,
    private val port: Int = Constants.DSH_DEFAULT_PORT,
    private val path: String = "/",
    private val connectTimeoutMs: Int = 2_000,
    private val readTimeoutMs: Int = 3_000,
) : SandboxHealthChecker {

    override suspend fun check(): SandboxHealth = withContext(Dispatchers.IO) {
        val portOpen = isPortOpen(host, port, connectTimeoutMs)
        val httpAlive = if (portOpen) httpProbe() else false
        SandboxHealth(
            sandboxState = if (httpAlive) SandboxState.READY else SandboxState.RUNNING,
            dshProcessRunning = portOpen,
            portOpen = portOpen,
            webUiReady = httpAlive,
        )
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }

    private fun httpProbe(): Boolean =
        try {
            val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            connection.disconnect()
            // The server answered -> the webserver is alive. Do NOT restrict to
            // 2xx: DSH is an SPA, so a 302 (e.g. to /login) or a 404 on a local
            // route still proves the HTTP server is up. Restricting to 200-299
            // misclassified a live DSH as "not ready" and triggered endless
            // auto-restarts. 1xx are informational (no final response yet) and
            // must not count as ready; anything >= 200 indicates a real reply.
            code in 200..599
        } catch (_: Exception) {
            false
        }
}

fun SandboxHealth.toAppResult(): AppResult<SandboxHealth> =
    if (webUiReady) AppResult.Success(this)
    else AppResult.Failure(AppError("DSH_NOT_READY", "DSH WebUI is not ready", recoverable = true))
