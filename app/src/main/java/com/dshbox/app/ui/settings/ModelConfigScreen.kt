package com.dshbox.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.dshbox.app.DshApp
import com.dshbox.app.R
import com.dshbox.app.common.AppResult
import com.dshbox.app.config.DshConfigStatus
import com.dshbox.app.config.ProviderPreset
import com.dshbox.app.service.SandboxService
import kotlinx.coroutines.launch

/**
 * Model configuration screen: lets the user pick a provider preset, enter the
 * API key, optionally set it as the default model, and persist the config to
 * the sandbox's DSH settings.yaml / .credentials.yaml.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelConfigScreen(
    sandboxReady: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val configWriter = (context.applicationContext as DshApp).container.dshConfigWriter

    var selectedPreset by remember { mutableStateOf(ProviderPreset.KABUAI_GLM) }
    var selectedModel by remember { mutableStateOf(selectedPreset.models.first()) }
    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf(selectedPreset.baseURL) }
    var setAsDefault by remember { mutableStateOf(true) }
    var showKey by remember { mutableStateOf(false) }
    var configStatus by remember { mutableStateOf<DshConfigStatus?>(null) }
    var saving by remember { mutableStateOf(false) }

    // Load the existing config status once on entry.
    LaunchedEffect(Unit) {
        configStatus = configWriter.readConfigStatus()
    }

    BackHandler(onBack = onBack)

    fun performSave(restart: Boolean) {
        if (apiKey.isBlank()) {
            Toast.makeText(context, R.string.model_config_key_required, Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        scope.launch {
            val result = configWriter.writeModelConfig(
                preset = selectedPreset,
                apiKey = apiKey.trim(),
                setAsDefault = setAsDefault,
            )
            when (result) {
                is AppResult.Success -> {
                    configStatus = configWriter.readConfigStatus()
                    Toast.makeText(context, R.string.model_config_save_success, Toast.LENGTH_SHORT).show()
                    if (restart) SandboxService.restart(context)
                }
                is AppResult.Failure -> {
                    Toast.makeText(
                        context,
                        context.getString(R.string.model_config_save_failed, result.error.message),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
            saving = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.model_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.diagnostics_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // ── Form card: provider, model, api key, base url, set default ──
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ProviderDropdown(
                        value = selectedPreset,
                        options = ProviderPreset.entries.toList(),
                        label = { stringResource(R.string.model_config_provider) },
                        display = { it.displayName },
                        onSelect = { preset ->
                            selectedPreset = preset
                            selectedModel = preset.models.first()
                            baseUrl = preset.baseURL
                        },
                    )

                    ModelDropdown(
                        value = selectedModel,
                        options = selectedPreset.models,
                        label = { stringResource(R.string.model_config_model) },
                        display = { it.name },
                        onSelect = { selectedModel = it },
                    )

                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = { apiKey = it },
                        label = { Text(stringResource(R.string.model_config_api_key)) },
                        placeholder = { Text("sk-...") },
                        singleLine = true,
                        visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { showKey = !showKey }) {
                                Icon(
                                    imageVector = if (showKey) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                    contentDescription = stringResource(
                                        if (showKey) R.string.model_config_hide_key else R.string.model_config_show_key,
                                    ),
                                )
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )

                    OutlinedTextField(
                        value = baseUrl,
                        onValueChange = { baseUrl = it },
                        label = { Text(stringResource(R.string.model_config_base_url)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.model_config_set_default),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = setAsDefault,
                            onCheckedChange = { setAsDefault = it },
                        )
                    }
                }
            }

            // ── Actions card: save buttons ──────────────────────────────────
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(2.dp, MaterialTheme.shapes.large),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(
                        onClick = { performSave(restart = true) },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                    ) {
                        Text(stringResource(R.string.model_config_save_restart))
                    }
                    OutlinedButton(
                        onClick = { performSave(restart = false) },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                    ) {
                        Text(stringResource(R.string.model_config_save_only))
                    }
                }
            }

            // ── Current config status card ───────────────────────────────────
            ConfigStatusCard(status = configStatus)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProviderDropdown(
    value: ProviderPreset,
    options: List<ProviderPreset>,
    label: @Composable () -> String,
    display: (ProviderPreset) -> String,
    onSelect: (ProviderPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label()) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelDropdown(
    value: com.dshbox.app.config.ModelPreset,
    options: List<com.dshbox.app.config.ModelPreset>,
    label: @Composable () -> String,
    display: (com.dshbox.app.config.ModelPreset) -> String,
    onSelect: (com.dshbox.app.config.ModelPreset) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label()) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(display(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfigStatusCard(status: DshConfigStatus?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(2.dp, MaterialTheme.shapes.large),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.model_config_status_title),
                style = MaterialTheme.typography.titleSmall,
            )
            if (status == null) {
                Text(
                    text = stringResource(R.string.model_config_not_configured),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                StatusRow(stringResource(R.string.model_config_status_provider), status.providerRoute ?: "—")
                StatusRow(stringResource(R.string.model_config_status_model), status.modelId ?: "—")
                StatusRow(
                    stringResource(R.string.model_config_status_key),
                    if (status.apiKeySet) stringResource(R.string.model_config_key_set)
                    else stringResource(R.string.model_config_key_not_set),
                )
                if (status.baseURL != null) {
                    StatusRow(stringResource(R.string.model_config_base_url), status.baseURL)
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}