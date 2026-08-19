package com.dshbox.app.ui.web

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.viewinterop.AndroidView
import android.util.Log
import com.dshbox.app.BuildConfig
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.bridge.BridgeRouter
import com.dshbox.app.bridge.model.CommandRequest
import com.dshbox.app.bridge.security.OriginVerifier
import com.dshbox.app.common.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * In-app WebView tab for the DSH WebUI, replacing the system browser.
 *
 * Responsibilities:
 * - Loads [Constants.DSH_BASE_URL] (loopback; cleartext is allowed for
 *   127.0.0.1/localhost by the app network security config).
 * - Mobile viewport: wide viewport + overview + built-in zoom so the desktop
 *   DSH UI stays usable on a phone screen.
 * - Toolbar: back / forward / address bar / refresh plus a loading progress bar.
 * - JS bridge: exposes `window.__dsh_android_bridge` via
 *   [addJavascriptInterface] and injects `window.__dsh_capability_token`
 *   (the BridgeRouter capability token) only into pages served by the real DSH
 *   WebUI (loopback + DSH port). External pages never see the token.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebViewScreen(
    bridgeRouter: BridgeRouter,
    onOpenWorkspacePicker: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val webView = remember { WebView(context) }

    // The JS bridge instance created inside the AndroidView factory; kept here
    // so the bridge's internal coroutine scope can be cancelled on teardown.
    var jsBridge by remember { mutableStateOf<DshJsBridge?>(null) }

    // Observable UI state, driven from WebViewClient / WebChromeClient callbacks.
    var currentUrl by remember { mutableStateOf(Constants.DSH_BASE_URL) }
    var progress by remember { mutableIntStateOf(0) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }

    var addressText by rememberSaveable { mutableStateOf(Constants.DSH_BASE_URL) }
    val focusManager = LocalFocusManager.current

    // While a page load (or user navigation) changes the URL, keep the address
    // bar in sync with reality.
    LaunchedEffect(currentUrl) {
        addressText = currentUrl
    }

    // WebView must be destroyed when this tab leaves composition (app teardown).
    DisposableEffect(webView) {
        onDispose {
            // Cancel any in-flight async bridge callbacks (execute etc.) and
            // detach the JS bridge before tearing down the WebView.
            jsBridge?.cancel()
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.loadUrl("about:blank")
            webView.destroy()
        }
    }

    Column(
        modifier = modifier.fillMaxSize(),
    ) {
        WebToolbar(
            currentUrl = currentUrl,
            canGoBack = canGoBack,
            canGoForward = canGoForward,
            onBack = { webView.goBack() },
            onForward = { webView.goForward() },
            onRefresh = { webView.reload() },
            onOpenWorkspacePicker = onOpenWorkspacePicker,
            addressText = addressText,
            onAddressTextChange = { addressText = it },
            onAddressSubmit = {
                focusManager.clearFocus()
                val target = normalizeUrl(addressText)
                addressText = target
                webView.loadUrl(target)
            },
            focusManager = focusManager,
        )
        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        } else {
            Spacer(modifier = Modifier.height(3.dp))
        }

        AndroidView(
            factory = {
                WebView(it).apply {
                    configureForDsh(webViewSettings = settings)
                    // Expose the JS bridge. The bridge gates every capability
                    // method on the current page still being the DSH WebUI origin.
                    val bridge = DshJsBridge(bridgeRouter = bridgeRouter, webView = this)
                    jsBridge = bridge
                    addJavascriptInterface(
                        bridge,
                        DshJsBridge.JS_NAME,
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                            currentUrl = url ?: Constants.DSH_BASE_URL
                            progress = 0
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            currentUrl = url ?: Constants.DSH_BASE_URL
                            canGoBack = view.canGoBack()
                            canGoForward = view.canGoForward()
                            // Inject the capability token only into pages served
                            // by the DSH WebUI itself (loopback + DSH port). A
                            // later navigation to an external page never gets it.
                            if (url != null && isDshWebUiOrigin(url)) {
                                view.evaluateJavascript(DshJsBridge.injectionScript(bridgeRouter.token), null)
                                // Mobile CSS: make the desktop DSH UI usable on a
                                // phone screen (full-width layout, hidden sidebar,
                                // touch-friendly tap targets, full-screen dialogs).
                                view.evaluateJavascript(injectMobileCss(), null)
                            }
                        }

                        override fun onReceivedError(
                            view: WebView,
                            errorCode: Int,
                            description: String,
                            failingUrl: String,
                        ) {
                            // Keep the platform default error page: the DSH
                            // runtime may be starting (RETRY/timeouts) and the
                            // user can hit refresh once it is ready.
                            currentUrl = failingUrl.ifEmpty { Constants.DSH_BASE_URL }
                            progress = 100
                        }
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            progress = newProgress
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            // no-op; address bar always shows the actual URL
                        }
                    }
                    loadUrl(Constants.DSH_BASE_URL)
                }
            },
            update = { /* config is static */ },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}

/**
 * Applies DSH-appropriate WebView settings: JavaScipt + DOM storage, mobile
 * viewport (wide view + overview + pinch zoom), no display zoom controls, and a
 * locked-down surface (no file access, no auto window opening).
 */
private fun WebView.configureForDsh(webViewSettings: WebSettings) {
    WebView.setWebContentsDebuggingEnabled(BuildConfig.ENABLE_WEBVIEW_DEBUGGING)

    webViewSettings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        useWideViewPort = true
        loadWithOverviewMode = true
        builtInZoomControls = true
        displayZoomControls = false
        setSupportZoom(true)
        mediaPlaybackRequiresUserGesture = false

        // Surface reduction: this WebView should only ever browse the local DSH
        // WebUI; file access, content:// URIs, autofill of sensitive data, and
        // popups are risks.
        allowFileAccess = false
        allowContentAccess = false
        javaScriptCanOpenWindowsAutomatically = false
    }
}

/**
 * Toolbar row: back / forward / address bar / refresh.
 */
@Composable
private fun WebToolbar(
    currentUrl: String,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    onRefresh: () -> Unit,
    onOpenWorkspacePicker: () -> Unit,
    addressText: String,
    onAddressTextChange: (String) -> Unit,
    onAddressSubmit: () -> Unit,
    focusManager: FocusManager,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(
                onClick = onBack,
                enabled = canGoBack,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.web_back),
                    tint = if (canGoBack) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                )
            }
            IconButton(
                onClick = onForward,
                enabled = canGoForward,
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(R.string.web_forward),
                    tint = if (canGoForward) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                )
            }
            TextField(
                value = addressText,
                onValueChange = onAddressTextChange,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Uri,
                    imeAction = ImeAction.Go,
                ),
                keyboardActions = KeyboardActions(
                    onGo = { onAddressSubmit() },
                ),
                placeholder = {
                    Text(
                        text = currentUrl,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                },
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                shape = RoundedCornerShape(10.dp),
            )
            IconButton(
                onClick = onRefresh,
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = stringResource(R.string.web_refresh),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            IconButton(
                onClick = onOpenWorkspacePicker,
            ) {
                Icon(
                    imageVector = Icons.Filled.Folder,
                    contentDescription = stringResource(R.string.workspace_title),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * The native object exposed to page JavaScript as `window.__dsh_android_bridge`.
 *
 * Exposes BridgeApi suspend methods to WebView JS:
 * - **Sync methods** (via runBlocking on Dispatchers.IO): listDirectory, stat,
 *   createDirectory, clipboardRead, clipboardWrite, getCurrentWorkspace,
 *   setCurrentWorkspace.
 * - **Async methods** (callback pattern via CoroutineScope): execute, showNotification.
 *
 * Every method guards with [isDshWebUiOrigin] — methods return empty/default
 * values when the page is not the DSH WebUI (loopback + DSH port).
 *
 * Filesystem methods enforce path restriction under `/root/projects` and
 * use the capability token injection for defense-in-depth.
 *
 * ## JS callback contract
 * The async methods invoke:
 * ```js
 * window.__dsh_bridge_callback(callbackId, payload)
 * ```
 * where `payload` is a JS object with the result fields.
 */
@SuppressLint("JavascriptInterface")
private class DshJsBridge(
    private val bridgeRouter: BridgeRouter,
    private val webView: WebView,
) {
    companion object {
        /** JS global name; also used for [addJavascriptInterface]. */
        const val JS_NAME = "__dsh_android_bridge"

        /** JS global that receives the capability token on DSH page loads. */
        const val TOKEN_GLOBAL = "__dsh_capability_token"

        /** JS global set to true once the bridge token handshake completed. */
        const val READY_GLOBAL = "__dsh_bridge_ready"

        /** JS global called by async methods with (callbackId, payload). */
        const val CALLBACK_GLOBAL = "__dsh_bridge_callback"

        /** Builds the onPageFinished injection for a given [token]. */
        fun injectionScript(token: String): String =
            "window.$TOKEN_GLOBAL='$token';window.$READY_GLOBAL=true;"

        private const val TAG = "DshJsBridge"
        private const val WORKSPACE_ROOT = "/root/projects"
        private const val EXEC_TIMEOUT_MS = 30_000L
    }

    /** Coroutine scope for async bridge methods; cancelled when the bridge is destroyed. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Host-side user-data directory mapped to guest `/root/projects`.
     * Resolved lazily from the application context so the bridge can convert
     * host paths (returned by the sandbox API) back to guest format for JS.
     */
    private val userDataDir: File by lazy {
        val app = webView.context.applicationContext as DshApp
        app.container.sandboxConfig.userDataDir
    }

    // ── Security helpers ───────────────────────────────────────────────────

    /** True when the WebView is currently on the DSH WebUI (loopback + DSH port). */
    private fun isTrusted(): Boolean {
        val url = webView.url ?: return false
        return isDshWebUiOrigin(url)
    }

    /**
     * Restricts filesystem paths to the workspace root. The sandbox bridge's
     * own [guestToHost] provides deeper path-traversal protection; this is a
     * first-line guard at the WebView layer.
     */
    private fun isAllowedWorkspacePath(path: String): Boolean {
        val trimmed = path.trim()
        if (trimmed != WORKSPACE_ROOT && !trimmed.startsWith("$WORKSPACE_ROOT/")) return false
        return trimmed.split('/').none { it == ".." }
    }

    /**
     * Converts a host absolute path (e.g. `/data/.../user-data/foo`) back to
     * the guest format (`/root/projects/foo`) so that JS round-trips work.
     * Falls back to the raw path when the host path is outside userDataDir.
     */
    private fun hostToGuestPath(hostPath: String): String {
        val prefix = try { userDataDir.canonicalPath } catch (_: Exception) { userDataDir.absolutePath }
        return when {
            hostPath == prefix -> WORKSPACE_ROOT
            hostPath.startsWith("$prefix/") -> "$WORKSPACE_ROOT/${hostPath.removePrefix("$prefix/")}"
            else -> hostPath
        }
    }

    // ── Sync methods (runBlocking on IO) ───────────────────────────────────

    /** Lists directory contents. Returns JSON array or `[]` on failure. */
    @JavascriptInterface
    fun listDirectory(path: String): String {
        if (!isTrusted()) return "[]"
        if (!isAllowedWorkspacePath(path)) return "[]"
        return try {
            val entries = runBlocking(Dispatchers.IO) {
                bridgeRouter.api.listDirectory(path)
            }
            JSONArray(entries.map { entry ->
                JSONObject().apply {
                    put("name", entry.name)
                    put("isDirectory", entry.isDirectory)
                    put("path", hostToGuestPath(entry.path))
                }
            }).toString()
        } catch (t: Throwable) {
            Log.w(TAG, "listDirectory($path) failed: ${t.message}")
            "[]"
        }
    }

    /** Returns file/directory metadata as JSON, or `{}` on failure. */
    @JavascriptInterface
    fun stat(path: String): String {
        if (!isTrusted()) return "{}"
        if (!isAllowedWorkspacePath(path)) return "{}"
        return try {
            val entry = runBlocking(Dispatchers.IO) {
                bridgeRouter.api.stat(path)
            }
            JSONObject().apply {
                put("name", entry.name)
                put("isDirectory", entry.isDirectory)
                put("path", hostToGuestPath(entry.path))
                put("size", entry.sizeBytes ?: JSONObject.NULL)
                put("modifiedAt", entry.modifiedAtMs ?: JSONObject.NULL)
            }.toString()
        } catch (t: Throwable) {
            Log.w(TAG, "stat($path) failed: ${t.message}")
            "{}"
        }
    }

    /** Creates a directory (and any missing parents). Returns true on success. */
    @JavascriptInterface
    fun createDirectory(path: String): Boolean {
        if (!isTrusted()) return false
        if (!isAllowedWorkspacePath(path)) return false
        return try {
            runBlocking(Dispatchers.IO) { bridgeRouter.api.createDirectory(path) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "createDirectory($path) failed: ${t.message}")
            false
        }
    }

    /** Reads the system clipboard. Returns empty string on failure / no content. */
    @JavascriptInterface
    fun clipboardRead(): String {
        if (!isTrusted()) return ""
        return try {
            runBlocking(Dispatchers.IO) { bridgeRouter.api.clipboardRead() }
        } catch (t: Throwable) {
            Log.w(TAG, "clipboardRead failed: ${t.message}")
            ""
        }
    }

    /** Writes text to the system clipboard. Returns true on success. */
    @JavascriptInterface
    fun clipboardWrite(text: String): Boolean {
        if (!isTrusted()) return false
        return try {
            runBlocking(Dispatchers.IO) { bridgeRouter.api.clipboardWrite(text) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "clipboardWrite failed: ${t.message}")
            false
        }
    }

    /** Returns the current workspace path (guest format, e.g. `/root/projects/...`). */
    @JavascriptInterface
    fun getCurrentWorkspace(): String {
        if (!isTrusted()) return ""
        return try {
            runBlocking(Dispatchers.IO) { bridgeRouter.api.getCurrentWorkspace() }
        } catch (t: Throwable) {
            Log.w(TAG, "getCurrentWorkspace failed: ${t.message}")
            ""
        }
    }

    /**
     * Sets the current workspace path. Returns true on success.
     * Path must be under `/root/projects`.
     */
    @JavascriptInterface
    fun setCurrentWorkspace(path: String): Boolean {
        if (!isTrusted()) return false
        if (!isAllowedWorkspacePath(path)) return false
        return try {
            runBlocking(Dispatchers.IO) { bridgeRouter.api.setCurrentWorkspace(path) }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "setCurrentWorkspace($path) failed: ${t.message}")
            false
        }
    }

    /** Handshake: lets the page confirm the native bridge is present. */
    @JavascriptInterface
    fun handshake(): String = "DSH_BRIDGE_OK"

    /** Returns the capability token for the DSH JS bridge handshake. */
    @JavascriptInterface
    fun getCapabilityToken(): String {
        val url = webView.url ?: return ""
        return if (isDshWebUiOrigin(url)) bridgeRouter.token else ""
    }

    // ── Async methods (callback pattern) ───────────────────────────────────

    /**
     * Executes a shell command inside the sandbox asynchronously.
     * On completion the result is delivered via [CALLBACK_GLOBAL].
     *
     * Callback payload:
     * ```json
     * {"callbackId":"...", "exitCode":0|null, "stdout":"...", "stderr":"...",
     *  "timedOut":false, "processId":123|null}
     * ```
     */
    @JavascriptInterface
    fun execute(command: String, callbackId: String) {
        if (!isTrusted()) {
            publishCallback(callbackId, """{"error":"untrusted origin"}""")
            return
        }
        if (callbackId.isBlank()) return
        scope.launch {
            try {
                val result = bridgeRouter.api.execute(
                    CommandRequest(command = command, timeoutMs = EXEC_TIMEOUT_MS)
                )
                val payload = JSONObject().apply {
                    put("callbackId", callbackId)
                    put("exitCode", result.exitCode ?: JSONObject.NULL)
                    put("stdout", result.stdout)
                    put("stderr", result.stderr)
                    put("timedOut", result.timedOut)
                    put("processId", result.processId ?: JSONObject.NULL)
                }
                publishCallback(callbackId, payload.toString())
            } catch (t: Throwable) {
                Log.w(TAG, "execute failed: ${t.message}")
                val err = JSONObject().apply {
                    put("callbackId", callbackId)
                    put("error", t.message ?: "unknown bridge error")
                }
                publishCallback(callbackId, err.toString())
            }
        }
    }

    /**
     * Shows an Android notification.
     * The optional [callbackId] is invoked with `{"ok":true}` on success or
     * `{"ok":false,"error":"..."}` on failure.
     */
    @JavascriptInterface
    fun showNotification(title: String, body: String, callbackId: String?) {
        if (!isTrusted()) {
            if (!callbackId.isNullOrBlank()) {
                publishCallback(callbackId, """{"ok":false,"error":"untrusted origin"}""")
            }
            return
        }
        scope.launch {
            try {
                bridgeRouter.api.showNotification(title, body)
                if (!callbackId.isNullOrBlank()) {
                    publishCallback(callbackId, """{"ok":true}""")
                }
            } catch (t: Throwable) {
                Log.w(TAG, "showNotification failed: ${t.message}")
                if (!callbackId.isNullOrBlank()) {
                    val err = JSONObject().apply {
                        put("ok", false)
                        put("error", t.message ?: "unknown")
                    }
                    publishCallback(callbackId, err.toString())
                }
            }
        }
    }

    // ── Internal helpers ───────────────────────────────────────────────────

    /**
     * Calls [CALLBACK_GLOBAL] on the JS side with the given [callbackId]
     * and [payloadJson] (a JSON object string that becomes a JS object literal).
     */
    private fun publishCallback(callbackId: String, payloadJson: String) {
        publishJs("window.$CALLBACK_GLOBAL && window.$CALLBACK_GLOBAL(" +
            jsStringLiteral(callbackId) + ", " +
            escapeJsonForJs(payloadJson) + ")")
    }

    /** Evaluates [script] in the WebView on the main thread. */
    private fun publishJs(script: String) {
        webView.post {
            try {
                webView.evaluateJavascript(script, null)
            } catch (_: Exception) {
                // WebView may be destroyed before the runnable fires
            }
        }
    }

    /**
     * Sanitize a string for safe embedding as a single-quoted JS string literal.
     * Escapes backslash, single-quote, newlines, and U+2028/U+2029 (line/paragraph
     * separators, which are valid in JSON but not in JS string literals).
     */
    private fun jsStringLiteral(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\u2028", "\\u2028")
            .replace("\u2029", "\\u2029")
        return "'$escaped'"
    }

    /**
     * Escapes only the characters that are valid in JSON but break when the
     * JSON text is embedded as a JS object literal expression (U+2028/U+2029).
     * JSONObject.toString() already handles all other escaping.
     */
    private fun escapeJsonForJs(json: String): String =
        json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    /** Cancel the internal coroutine scope; call before WebView destruction. */
    fun cancel() {
        scope.cancel()
    }
}

/**
 * Returns a JS snippet that injects mobile-friendly CSS into the DSH WebUI page.
 *
 * The DSH frontend uses CSS modules with hashed-prefix class names (e.g.
 * `pI_x6G_sidebarCol`).  Attribute-substring selectors like `[class*="sidebar"]`
 * still work because the semantic name survives after the hash prefix.  All
 * rules are wrapped in `@media (max-width: 600px)` so desktop/landscape
 * viewports keep the full layout.
 *
 * Rules:
 * - The 3-column shell grid (sidebar | center | details) collapses to a single
 *   column — the sidebar and details columns are hidden, and the center column
 *   fills the full viewport width.
 * - The resize handle is hidden.
 * - All interactive elements get at least 44px min-height per Material Design
 *   touch-target guidelines.
 * - Input, textarea, select get 16px font-size (prevents iOS auto-zoom).
 * - Dialogs and modals become full-screen overlays.
 * - Body text gets 16px base font-size for readability.
 * - Conversation message spacing is tightened for narrow screens.
 */
private fun injectMobileCss(): String = """
(function() {
  var s = document.createElement('style');
  s.textContent = `
    @media (max-width: 600px) {
      /* ── Layout: collapse the 3-column grid to a single column ── */
      [class$$="frame"] {
        grid-template-columns: 100% !important;
      }
      [class$$="handle"] {
        display: none !important;
      }

      /* ── Hide redundant desktop columns ── */
      [class*="sidebarCol"],
      [class*="detailsCol"] {
        display: none !important;
      }

      /* ── Center column fills the full viewport ── */
      [class*="centerCol"] {
        width: 100% !important;
        max-width: 100vw !important;
      }

      /* ── Touch-friendly interactive elements ── */
      button, a, [role="button"], input, select, textarea, [tabindex] {
        min-height: 44px !important;
        font-size: 16px !important;
      }
      input, textarea, select {
        font-size: 16px !important;
        padding: 12px !important;
      }

      /* ── Full-screen dialogs ── */
      [role="dialog"], [class*="modal"], [class*="dialog"] {
        position: fixed !important;
        top: 0 !important;
        left: 0 !important;
        width: 100vw !important;
        height: 100vh !important;
        max-width: 100vw !important;
        max-height: 100vh !important;
        margin: 0 !important;
        border-radius: 0 !important;
      }

      /* ── Body text readability ── */
      body, p, span, div, li {
        font-size: 16px !important;
        line-height: 1.5 !important;
      }

      /* ── Tighter conversation message spacing ── */
      [class*="scroll"] {
        padding: 8px 12px !important;
      }
    }
  `;
  document.head.appendChild(s);
})();
""".trimIndent()

/** True when [url] is served by the real DSH WebUI (loopback + DSH port). */
private fun isDshWebUiOrigin(url: String): Boolean {
    val origin = OriginVerifier.parse(url) ?: return false
    return origin.scheme == "http" &&
        origin.host in DSH_LOCAL_HOSTS &&
        origin.port == Constants.dshPort
}

private val DSH_LOCAL_HOSTS = setOf("127.0.0.1", "localhost", "::1")

/** Prepends http:// when the user typed a bare host/path. */
private fun normalizeUrl(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return Constants.DSH_BASE_URL
    val lowered = trimmed.lowercase()
    return if (lowered.startsWith("http://") || lowered.startsWith("https://")) {
        trimmed
    } else {
        "http://$trimmed"
    }
}