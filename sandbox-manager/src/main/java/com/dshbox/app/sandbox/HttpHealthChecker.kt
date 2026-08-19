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
 *
 * While the body is fetched anyway, the index page is parsed for version
 * metadata: DSH injects `window.__DSH_BOOT__` (the composed client-modules
 * graph) server-side into every index response. Its `rev` field is the
 * build fingerprint of the running DSH WebUI, used as the reported version
 * when the server provides no explicit semver. If a future DSH serves a
 * top-level `version`/`pluginApiVersion` inside the manifest, those take
 * priority.
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
        val probe = if (portOpen) httpProbe() else null
        val webUiReady = probe != null
        SandboxHealth(
            sandboxState = if (webUiReady) SandboxState.READY else SandboxState.RUNNING,
            dshProcessRunning = portOpen,
            portOpen = portOpen,
            webUiReady = webUiReady,
            dshVersion = probe?.dshVersion,
            pluginApiVersion = probe?.pluginApiVersion,
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

    /** Result of an HTTP probe: alive flag plus version metadata parsed from the body. */
    internal data class ProbeResult(val dshVersion: String?, val pluginApiVersion: String?)

    private fun httpProbe(): ProbeResult? {
        return try {
            val connection = URL("http://$host:$port$path").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            val code = connection.responseCode
            // The server answered -> the webserver is alive. Do NOT restrict to
            // 2xx: DSH is an SPA, so a 302 (e.g. to /login) or a 404 on a local
            // route still proves the HTTP server is up. Restricting to 200-299
            // misclassified a live DSH as "not ready" and triggered endless
            // auto-restarts. 1xx are informational (no final response yet) and
            // must not count as ready; anything >= 200 indicates a real reply.
            if (code !in 200..599) return null
            val body = runCatching { connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
                .getOrNull()
            connection.disconnect()
            parseVersionInfo(body)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse DSH version metadata from the index page body.
     *
     * DSH injects `window.__DSH_BOOT__ = {...}` into every index response.
     * The object shape is `{rev, entries}` where `rev` is the composed
     * graph fingerprint. Explicit `version`/`pluginApiVersion` fields are
     * honored first (a future DSH may add them); otherwise the graph `rev`
     * identifies the installed WebUI build.
     */
    internal fun parseVersionInfo(body: String?): ProbeResult {
        if (body.isNullOrBlank()) return ProbeResult(null, null)
        val start = body.indexOf("window.__DSH_BOOT__")
        if (start < 0) return ProbeResult(null, null)
        val braceStart = body.indexOf('{', start)
        if (braceStart < 0) return ProbeResult(null, null)
        // Brace-count to find the matching closing brace.
        var depth = 0
        var end = -1
        for (i in braceStart until body.length) {
            when (body[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end < 0) return ProbeResult(null, null)
        val boot = body.substring(braceStart, end + 1)

        val rev = Regex("""(?:"rev"|'rev')\s*:\s*"([^"]+)"""").find(boot)?.groupValues?.getOrNull(1)
        // Explicit version fields win over the fingerprint.
        val dshVersion = Regex("""(?:"version"|'version')\s*:\s*"([^"]+)"""").find(boot)
            ?.groupValues?.getOrNull(1)
            ?: rev
        val pluginApiVersion = Regex("""(?:"pluginApiVersion"|'pluginApiVersion')\s*:\s*"([^"]+)"""").find(boot)
            ?.groupValues?.getOrNull(1)
        return ProbeResult(dshVersion, pluginApiVersion)
    }
}

fun SandboxHealth.toAppResult(): AppResult<SandboxHealth> =
    if (webUiReady) AppResult.Success(this)
    else AppResult.Failure(AppError("DSH_NOT_READY", "DSH WebUI is not ready", recoverable = true))