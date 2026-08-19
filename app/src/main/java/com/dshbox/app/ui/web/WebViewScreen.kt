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
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.bridge.BridgeRouter
import com.dshbox.app.bridge.security.OriginVerifier
import com.dshbox.app.common.Constants

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
                    // Expose the JS bridge. The bridge gates the token on the
                    // current page still being the DSH WebUI origin.
                    addJavascriptInterface(
                        DshJsBridge(bridgeRouter = bridgeRouter, webView = this),
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
 * The capability token is only handed out while the WebView is still on the DSH
 * WebUI origin — after navigating to an external page the getter returns "".
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

        /** Builds the onPageFinished injection for a given [token]. */
        fun injectionScript(token: String): String =
            "window.$TOKEN_GLOBAL='$token';window.$READY_GLOBAL=true;"

        private val TAG = "DshJsBridge"
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
}

/**
 * Returns a JS snippet that injects mobile-friendly CSS into the DSH WebUI page.
 *
 * Rules:
 * - Main/center containers fill the full viewport width; sidebars and details are
 *   hidden so the core content uses the whole screen.
 * - All interactive elements (buttons, links, inputs, tabbable items) get at least
 *   44px min-height per Material Design touch-target guidelines.
 * - Input, textarea, select get 16px font-size (prevents iOS auto-zoom) and 12px
 *   padding.
 * - Dialogs and modals become full-screen overlays (no rounded corners, no margin).
 * - Body text gets 16px base font-size and 1.5 line-height for readability.
 */
private fun injectMobileCss(): String = """
(function() {
  var s = document.createElement('style');
  s.textContent = `
    [class*="center"], [class*="main"] {
      width: 100vw !important;
      max-width: 100vw !important;
      min-width: 100vw !important;
    }
    [class*="sidebar"] {
      display: none !important;
    }
    [class*="details"] {
      display: none !important;
    }
    button, a, [role="button"], input, select, textarea, [tabindex] {
      min-height: 44px !important;
      font-size: 16px !important;
    }
    input, textarea, select {
      font-size: 16px !important;
      padding: 12px !important;
    }
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
    body, p, span, div, li {
      font-size: 16px !important;
      line-height: 1.5 !important;
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