package com.dshbox.app.ui

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.sandbox.BundledRuntimeInstaller
import com.dshbox.app.sandbox.SandboxState
import com.dshbox.app.ui.files.FilesScreen
import com.dshbox.app.ui.home.HomeScreen
import com.dshbox.app.ui.launch.LaunchScreen
import com.dshbox.app.ui.sandbox.SandboxScreen
import com.dshbox.app.ui.settings.SettingsScreen
import com.dshbox.app.ui.theme.AppIcons
import com.dshbox.app.ui.web.WebViewScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/** Height of the app's own bottom NavigationBar (Material3 default 80dp). */
internal val AppNavBarHeightDp = 80.dp

private data class TabSpec(val labelRes: Int, val icon: AppIcons)

/** Minimum time the brand launch animation stays visible on cold start. */
private const val SPLASH_MIN_MILLIS = 2_000L

/** Upper bound for keeping the splash up during first-boot extraction (8 min). */
private const val SPLASH_FIRST_BOOT_CAP_MILLIS = 8 * 60_000L

@Composable
fun MainScreen() {
    val app = LocalContext.current.applicationContext as DshApp
    val sandboxManager = app.container.sandboxManager

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    // Sandbox state is runtime truth, NOT process state: rememberSaveable
    // would restore READY (sandboxReady=1) from a previous process on every
    // cold start, which hid the splash (or flashed the home UI first) until
    // the state flow re-emitted UNINITIALIZED. remember() starts fresh.
    var sandboxReady by remember { mutableIntStateOf(0) } // 0/1
    var sandboxError by remember { mutableIntStateOf(0) } // 0/1
    var sandboxStopped by remember { mutableIntStateOf(0) } // 0/1
    var showLaunch by remember { mutableStateOf(true) }
    var runtimeInstalled by remember { mutableStateOf(sandboxManager.isRuntimeInstalled()) }
    val bundledRuntimeAvailable = remember {
        BundledRuntimeInstaller(app, app.container.sandboxConfig).hasBundledBundle()
    }

    // Android 13+ (API 33+): POST_NOTIFICATIONS must be granted at runtime or
    // the foreground-service notification is blocked by the system. Declared in
    // the manifest; request it once on first launch.
    val context = LocalContext.current
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* granted/denied — the foreground service still runs */ }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val tabs = listOf(
        TabSpec(R.string.tab_home, AppIcons.Home),
        TabSpec(R.string.tab_files, AppIcons.Files),
        TabSpec(R.string.tab_sandbox, AppIcons.Sandbox),
        TabSpec(R.string.tab_settings, AppIcons.Settings),
        TabSpec(R.string.tab_web, AppIcons.Web),
    )

    LaunchedEffect(sandboxManager) {
        sandboxManager.state.collectLatest { state ->
            sandboxReady = if (state == SandboxState.READY) 1 else 0
            sandboxError = if (state == SandboxState.ERROR) 1 else 0
            sandboxStopped = if (state == SandboxState.STOPPED) 1 else 0
            // First boot extracts the bundled runtime asynchronously; refresh
            // the flag with every state change so the "install runtime" banner
            // disappears once the sandbox actually has a runtime.
            runtimeInstalled = sandboxManager.isRuntimeInstalled()
            // Dismiss the launch animation only when the sandbox settles into
            // a terminal state. STOPPED is NOT terminal here: on every cold
            // start the manager passes through STOPPED right after
            // initialize() and before start(), so closing on STOPPED would
            // make the brand splash invisible.
            if (state == SandboxState.READY ||
                state == SandboxState.ERROR
            ) {
                showLaunch = false
            }
        }
    }

    // Brand splash minimum duration: with the runtime already installed the
    // sandbox reaches READY within seconds, so without a floor the launch
    // animation would be invisible on every cold start.
    //
    // On first boot (bundled runtime being extracted) the splash stays up so
    // the extraction progress hint is visible; a hard cap prevents a deadlock
    // if extraction hangs or the bundle is missing/corrupt — the user must
    // always reach the Home screen's import CTA.
    val firstBootInstall = !runtimeInstalled && bundledRuntimeAvailable
    LaunchedEffect(Unit) {
        delay(SPLASH_MIN_MILLIS)
        if (firstBootInstall) {
            val deadline = System.currentTimeMillis() + SPLASH_FIRST_BOOT_CAP_MILLIS
            while (showLaunch && System.currentTimeMillis() < deadline) {
                delay(250)
                if (runtimeInstalled || sandboxError == 1) break
            }
        }
        showLaunch = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        val splashVisible = showLaunch && sandboxReady == 0
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                // While the splash is up the main UI must not paint even one
                // frame (otherwise the first frame flashes the home content
                // before the overlay draws). Both changes land in the same
                // recomposition, so there is no intermediate frame.
                .alpha(if (splashVisible) 0f else 1f),
            bottomBar = {
                IosTabBar(
                    tabs = tabs,
                    selectedTab = selectedTab,
                    onSelect = { selectedTab = it },
                )
            },
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                TabContent(
                    selectedTab = selectedTab,
                    sandboxReady = sandboxReady == 1,
                    sandboxError = sandboxError == 1,
                    sandboxStopped = sandboxStopped == 1,
                    runtimeInstalled = runtimeInstalled,
                    bundledRuntimeAvailable = bundledRuntimeAvailable,
                    onNavigateToSettings = { selectedTab = 3 },
                    onOpenWebUI = { selectedTab = 4 },
                )
            }
        }

        if (showLaunch && sandboxReady == 0) {
            LaunchScreen(
                runtimeInstalled = runtimeInstalled,
                bundledRuntimeAvailable = bundledRuntimeAvailable,
            )
        }
    }
}

@Composable
private fun TabContent(
    selectedTab: Int,
    sandboxReady: Boolean,
    sandboxError: Boolean,
    sandboxStopped: Boolean,
    runtimeInstalled: Boolean,
    bundledRuntimeAvailable: Boolean,
    onNavigateToSettings: () -> Unit,
    onOpenWebUI: () -> Unit,
) {
    // Lazy creation: only create the WebView when the user first switches to
    // the Web tab. After that, it stays composed (keep alive) so the DSH SPA
    // session is preserved across tab switches. This avoids creating a WebView
    // (and loading an error page) at app startup before the sandbox is READY.
    var webTabCreated by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) {
        if (selectedTab == 4) webTabCreated = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // The selected tab fades in with a subtle spring; every tab stays
        // composed (so the terminal keeps running), hidden tabs only lose
        // alpha, hit-testing and accessibility.
        AnimatedTab(visible = selectedTab == 0, zIndex = if (selectedTab == 0) 1f else 0f) {
            HomeScreen(
                sandboxReady = sandboxReady,
                sandboxError = sandboxError,
                sandboxStopped = sandboxStopped,
                runtimeInstalled = runtimeInstalled,
                bundledRuntimeAvailable = bundledRuntimeAvailable,
                onNavigateToSettings = onNavigateToSettings,
                onOpenWebUI = onOpenWebUI,
            )
        }
        AnimatedTab(visible = selectedTab == 1, zIndex = if (selectedTab == 1) 1f else 0f) {
            FilesScreen(
                isActiveTab = selectedTab == 1,
            )
        }
        AnimatedTab(visible = selectedTab == 2, zIndex = if (selectedTab == 2) 1f else 0f) {
            SandboxScreen(
                sandboxReady = sandboxReady,
                sandboxStopped = sandboxStopped,
                onNavigateToSettings = onNavigateToSettings,
                isActiveTab = selectedTab == 2,
            )
        }
        AnimatedTab(visible = selectedTab == 3, zIndex = if (selectedTab == 3) 1f else 0f) {
            SettingsScreen(
                sandboxReady = sandboxReady,
            )
        }
        // The Web tab is only composed after the first visit; hidden tabs keep
        // running, hit-testing and accessibility stay on the visible tab.
        if (webTabCreated) {
            AnimatedTab(visible = selectedTab == 4, zIndex = if (selectedTab == 4) 1f else 0f) {
                val app = LocalContext.current.applicationContext as DshApp
                WebViewScreen(
                    bridgeRouter = app.container.bridgeRouter,
                )
            }
        }
    }
}

/**
 * Wraps one tab screen: animates alpha with a soft spring on activation,
 * removes hidden tabs from hit-testing and the accessibility tree.
 */
@Composable
private fun AnimatedTab(
    visible: Boolean,
    zIndex: Float,
    content: @Composable () -> Unit,
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 260f),
        label = "tab-alpha",
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(zIndex)
            .alpha(animatedAlpha)
            .then(if (visible) Modifier else Modifier.hiddenTab()),
    ) {
        content()
    }
}

/**
 * Keeps a non-selected tab composed (so the terminal keeps running) while
 * removing it from the accessibility tree and from hit-testing, so taps on
 * the visible tab can never reach hidden screens below.
 *
 * The pointer-input loop suspends on every gesture (it does not poll), so it
 * consumes no CPU while idle; it only marks incoming pointer events as consumed
 * so the hidden screen behind the alpha(0f) overlay can never react to taps
 * intended for the visible tab.
 *
 * Optimization over the naive consume-everything approach: the Main pass runs
 * parent-before-child, so by consuming here the hidden tab's own children
 * (e.g. a hidden terminal input) never see an unconsumed event, while events
 * the visible tab already handled are skipped instead of being re-consumed.
 */
private fun Modifier.hiddenTab(): Modifier = this
    .semantics(mergeDescendants = false) { invisibleToUser() }
    .pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                awaitPointerEvent().changes.forEach { change ->
                    // Consume only leftovers: events the visible (top) tab already
                    // consumed pass through; anything unclaimed gets swallowed so
                    // the hidden tab below can never react to it.
                    if (!change.isConsumed) change.consume()
                }
            }
        }
    }

/**
 * iOS-style bottom tab bar: row of icon + small label items on a translucent
 * floating surface. Selected item gets the accent (green) tint, unselected
 * falls back to a neutral system gray — matching the iOS tab bar look instead
 * of the Material3 indicator pill.
 */
@Composable
private fun IosTabBar(
    tabs: List<TabSpec>,
    selectedTab: Int,
    onSelect: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEachIndexed { index, tab ->
                val selected = selectedTab == index
                val label = stringResource(tab.labelRes)
                val tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                }
                val iconSize by animateDpAsState(
                    targetValue = if (selected) 26.dp else 24.dp,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
                    label = "tab-icon-size",
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = { onSelect(index) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = tab.icon.imageVector,
                        contentDescription = label,
                        tint = tint,
                        modifier = Modifier.size(iconSize),
                    )
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = tint,
                        maxLines = 1,
                    )
                    // Active indicator: a small primary-colored capsule under the
                    // selected tab label, spring-animated for a polished feel.
                    Spacer(Modifier.height(2.dp))
                    val indicatorWidth by animateDpAsState(
                        targetValue = if (selected) 18.dp else 0.dp,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 500f),
                        label = "tab-indicator-width",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = indicatorWidth, height = 3.dp)
                            .clip(RoundedCornerShape(1.5.dp))
                            .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent),
                    )
                }
            }
        }
    }
}
