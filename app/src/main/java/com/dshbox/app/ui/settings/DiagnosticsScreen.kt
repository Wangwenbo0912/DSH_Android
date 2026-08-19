package com.dshbox.app.ui.settings

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dshbox.app.BuildConfig
import com.dshbox.app.R
import com.dshbox.app.common.Constants
import com.dshbox.app.util.formatFileSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    sandboxReady: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val logsDir = remember { File(context.filesDir, "logs") }
    var logFiles by remember { mutableStateOf(listOf<File>()) }
    var selectedName by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    // Refresh log file list.
    LaunchedEffect(refreshKey) {
        val files = runCatching {
            logsDir.listFiles { f -> f.isFile && f.name.startsWith("process-") && f.name.endsWith(".log") }
                ?.sortedByDescending { it.lastModified() }
                .orEmpty()
        }.getOrDefault(emptyList())
        logFiles = files
        // Keep selection valid; auto-select first if none selected.
        if (selectedName == null || files.none { it.name == selectedName }) {
            selectedName = files.firstOrNull()?.name
        }
    }

    // Tail of the selected log file. Loading is done off the main thread via
    // LaunchedEffect + Dispatchers.IO so that reading large files does not
    // block composition (the original remember { … readLines() … } ran on
    // the main thread, causing jank / ANR risk).
    var logContent by remember { mutableStateOf("") }
    LaunchedEffect(selectedName, refreshKey) {
        val name = selectedName
        if (name == null) {
            logContent = ""
            return@LaunchedEffect
        }
        logContent = withContext(Dispatchers.IO) {
            runCatching { File(logsDir, name).readLines().takeLast(400).joinToString("\n") }
                .getOrDefault("")
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(logContent.toByteArray())
                }
            }
            Toast.makeText(context, R.string.diagnostics_export_done, Toast.LENGTH_SHORT).show()
        }
    }

    fun shareLog() {
        if (logContent.isBlank()) {
            Toast.makeText(context, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "DSH 日志 - ${selectedName ?: "sandbox"}")
            putExtra(Intent.EXTRA_TEXT, logContent)
        }
        runCatching {
            context.startActivity(Intent.createChooser(send, context.getString(R.string.diagnostics_log_share)))
        }.onFailure {
            Toast.makeText(context, R.string.diagnostics_share_failed, Toast.LENGTH_SHORT).show()
        }
    }

    BackHandler(onBack = onBack)

    // ── Device info ──────────────────────────────────────────────────────────
    val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"
    val androidVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
    val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
    val ramMb = runCatching {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        mi.totalMem / (1024L * 1024L)
    }.getOrDefault(0L)
    val ramText = if (ramMb > 0) String.format(Locale.US, "%.1f GB", ramMb / 1024f) else "?"
    val appVersion = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    Column(modifier = modifier.fillMaxSize()) {
        // ── Header row ───────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.diagnostics_back))
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = stringResource(R.string.settings_diagnostics),
                style = MaterialTheme.typography.titleLarge,
            )
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { refreshKey++ }) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = stringResource(R.string.diagnostics_refresh),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()

        // ── Scrollable content ───────────────────────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── Status summary ───────────────────────────────────────────────
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.diagnostics_dsh_address) + "：" + Constants.DSH_BASE_URL,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = stringResource(
                            if (sandboxReady) R.string.diagnostics_status_running
                            else R.string.diagnostics_status_not_ready,
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // ── Device info section ──────────────────────────────────────────
            Text(
                text = stringResource(R.string.diagnostics_device_section),
                style = MaterialTheme.typography.titleMedium,
            )
            DeviceInfoRow(
                label = stringResource(R.string.diagnostics_device_model),
                value = deviceModel,
            )
            DeviceInfoRow(
                label = stringResource(R.string.diagnostics_device_android),
                value = androidVersion,
            )
            DeviceInfoRow(
                label = stringResource(R.string.diagnostics_device_abi),
                value = abi,
            )
            DeviceInfoRow(
                label = stringResource(R.string.diagnostics_device_ram),
                value = ramText,
            )
            DeviceInfoRow(
                label = stringResource(R.string.diagnostics_device_app),
                value = appVersion,
            )

            HorizontalDivider()

            // ── Log files section ────────────────────────────────────────────
            Text(
                text = stringResource(R.string.diagnostics_log_section_title),
                style = MaterialTheme.typography.titleMedium,
            )

            if (logFiles.isEmpty()) {
                Text(
                    text = stringResource(R.string.diagnostics_log_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            } else {
                // Log file list (short list, no LazyColumn = safe in verticalScroll).
                logFiles.forEach { file ->
                    val isSelected = file.name == selectedName
                    val sizeStr = formatFileSize(file.length())
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f)
                                else MaterialTheme.colorScheme.surface
                            )
                            .clickable { selectedName = file.name }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = file.name,
                                fontSize = 14.sp,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (isSelected) MaterialTheme.colorScheme.onSecondaryContainer
                                else MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sizeStr,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }

            // ── Log content preview ──────────────────────────────────────────
            if (selectedName != null && logContent.isNotBlank()) {
                Text(
                    text = stringResource(R.string.diagnostics_log_view, 400),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Text(
                        text = logContent,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(12.dp),
                    )
                }
            }

            // ── Action buttons ───────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { exportLauncher.launch("dsh-log.txt") },
                    enabled = logContent.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.diagnostics_export))
                }
                Button(
                    shape = MaterialTheme.shapes.medium,
                    onClick = { shareLog() },
                    enabled = logContent.isNotBlank(),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Share,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.diagnostics_log_share))
                }
            }
        }
    }
}

@Composable
private fun DeviceInfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(0.65f),
        )
    }
}