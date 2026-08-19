package com.dshbox.app.ui.workspace

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dshbox.app.DshApp
import com.dshbox.app.bridge.model.FileEntry
import kotlinx.coroutines.launch

/**
 * Full-screen native workspace picker.
 *
 * Navigates the guest workspace area (starting at `/root/projects`) by listing
 * subdirectories through the [com.dshbox.app.bridge.api.BridgeApi] (guest-format
 * paths; the bridge maps them to the host filesystem internally). Selecting a
 * directory stores it via [com.dshbox.app.workspace.WorkspaceManager] and syncs
 * it to the bridge's current workspace so subsequent sandbox commands start
 * from the chosen folder.
 */
@Composable
fun WorkspacePickerScreen(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val app = LocalContext.current.applicationContext as DshApp
    val bridgeRouter = app.container.bridgeRouter
    val workspaceManager = app.container.workspaceManager
    val scope = rememberCoroutineScope()

    // The guest workspace root: everything under /root/projects is user work.
    val rootPath = "/root/projects"

    var currentPath by remember { mutableStateOf(rootPath) }
    var entries by remember { mutableStateOf<List<FileEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var listFailed by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }

    fun refresh() {
        scope.launch {
            isLoading = true
            listFailed = false
            try {
                val all = bridgeRouter.api.listDirectory(currentPath)
                entries = all.filter { it.isDirectory }
            } catch (t: Throwable) {
                listFailed = true
                entries = emptyList()
            }
            isLoading = false
        }
    }

    LaunchedEffect(currentPath) { refresh() }

    val canGoUp = currentPath != rootPath
    fun goUp() {
        if (canGoUp) {
            currentPath = currentPath.substringBeforeLast('/', rootPath)
        }
    }

    // System back navigates up; at the root it closes the picker.
    BackHandler {
        if (canGoUp) goUp() else onClose()
    }

    val segments = remember(currentPath) {
        currentPath.split('/').filter { it.isNotEmpty() }
    }
    // Clickable breadcrumb: each entry maps to the guest path prefix it names.
    val crumbs = remember(segments) {
        val list = mutableListOf<Pair<String, String>>() // (path, label)
        var acc = ""
        segments.forEach { seg ->
            acc += "/$seg"
            list += acc to seg
        }
        list
    }
    val hScroll = rememberScrollState()
    LaunchedEffect(crumbs.size, currentPath) {
        withFrameNanos { }
        hScroll.scrollTo(hScroll.maxValue.coerceAtLeast(0))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "关闭",
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                text = "选择工作区目录",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )

        // ── Breadcrumb + up ──────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = { goUp() },
                enabled = canGoUp,
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回上层",
                    tint = if (canGoUp) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                )
            }
            Spacer(Modifier.width(4.dp))
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(hScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A "/" prefix so the path reads like a real path.
                Text(
                    text = "/",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                crumbs.forEachIndexed { index, (path, label) ->
                    if (index > 0) {
                        Text(
                            text = "/",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        )
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = if (index == crumbs.lastIndex) FontWeight.Medium else FontWeight.Normal,
                        color = if (index == crumbs.lastIndex) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { currentPath = path },
                    )
                }
            }
        }

        // ── Directory list ───────────────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                listFailed -> {
                    Column(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "无法读取目录",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "运行环境可能尚未就绪，或目录已被移动。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(onClick = { refresh() }) {
                            Text("重试")
                        }
                    }
                }
                entries.isEmpty() -> {
                    Text(
                        text = "此目录下没有子文件夹",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp,
                            vertical = 4.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(entries, key = { it.name }) { entry ->
                            DirectoryRow(
                                name = entry.name,
                                onClick = {
                                    currentPath = "$currentPath/${entry.name}"
                                },
                            )
                        }
                    }
                }
            }
        }

        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        )

        // ── Bottom actions ───────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = currentPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { showNewFolderDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CreateNewFolder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("创建新目录")
                }
                Button(
                    onClick = {
                        // Persist the selection, sync the bridge workspace so
                        // sandbox commands execute from this folder, and write the
                        // record into the DSH storage registry (picked up on the
                        // next DSH restart).
                        workspaceManager.setWorkspace(currentPath)
                        scope.launch {
                            bridgeRouter.api.setCurrentWorkspace(currentPath)
                            workspaceManager.ensureWorkspaceInStorage(
                                guestPath = currentPath,
                                userDataDir = app.container.sandboxConfig.userDataDir,
                            )
                        }
                        onClose()
                    },
                    modifier = Modifier
                        .weight(1.3f)
                        .height(48.dp),
                ) {
                    Text("选择此目录为工作区")
                }
            }
        }
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            name = newFolderName,
            onNameChange = { newFolderName = it },
            onDismiss = {
                showNewFolderDialog = false
                newFolderName = ""
            },
            onConfirm = { name ->
                val safe = sanitizeDirName(name)
                if (safe != null) {
                    scope.launch {
                        bridgeRouter.api.createDirectory("$currentPath/$safe")
                    }
                    refresh()
                }
                showNewFolderDialog = false
                newFolderName = ""
            },
        )
    }
}

/** One tappable directory row. */
@Composable
private fun DirectoryRow(
    name: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Dialog for creating a new subdirectory in the current folder. */
@Composable
private fun NewFolderDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("创建新目录") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                singleLine = true,
                placeholder = { Text("请输入文件夹名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = { if (name.isNotBlank()) onConfirm(name) },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name) },
                enabled = name.isNotBlank(),
            ) {
                Text("创建")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        },
    )
}

/** Validates a directory name: non-empty, no path separators or traversal. */
private fun sanitizeDirName(raw: String): String? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed == "." || trimmed == "..") return null
    if (trimmed.contains('/') || trimmed.contains('\\')) return null
    return trimmed
}