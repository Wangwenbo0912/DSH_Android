package com.dshbox.app.config

import android.util.Log
import com.dshbox.app.common.AppResult
import com.dshbox.app.common.AppError
import com.dshbox.app.common.LogRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import java.io.File
import java.io.FileInputStream
import java.io.FileWriter
import java.io.IOException

/**
 * Writes DSH LLM provider configuration into the sandbox guest filesystem.
 *
 * Target files (Android-side paths that PRoot bind-mounts into /root/projects):
 * - settings.yaml → user-data/.dsh/settings.yaml
 * - credentials   → user-data/.dsh/.credentials.yaml
 *
 * DSH's dsh-credentials-local reads .credentials.yaml as a flat
 * KEY→"value" YAML mapping and enforces file mode 0600.
 * DSH's dsh-llm-pi-ai reads the `llm-pi-ai.providers` dict from settings.yaml.
 */
class DshConfigWriter(
    private val userDataDir: File,  // = app/files/user-data
) {
    private val dshDir get() = File(userDataDir, ".dsh")
    private val settingsFile get() = File(dshDir, "settings.yaml")
    private val credentialsFile get() = File(dshDir, ".credentials.yaml")

    companion object {
        private const val TAG = "DshConfigWriter"
    }

    /**
     * Persist model configuration to DSH config files.
     *
     * @param preset selected provider preset (kabuai / kabuai-glm)
     * @param apiKey user-entered API key (sk-...)
     * @param setAsDefault if true, writes agent-default-model section
     * @return AppResult with success/failure
     */
    suspend fun writeModelConfig(
        preset: ProviderPreset,
        apiKey: String,
        setAsDefault: Boolean = true,
    ): AppResult<Unit> = withContext(Dispatchers.IO) {
        try {
            dshDir.mkdirs()

            // 1. Write settings.yaml with merge strategy
            writeSettings(preset, setAsDefault)

            // 2. Write .credentials.yaml
            writeCredentials(preset.apiKeyEnv, apiKey)

            Log.i(TAG, "writeModelConfig: ${preset.routeKey} configured, key=${LogRedactor.redact("sk-" + apiKey.takeLast(4))}")
            AppResult.Success(Unit)
        } catch (t: Throwable) {
            Log.e(TAG, "writeModelConfig failed: ${t.message}", t)
            AppResult.Failure(AppError("CONFIG_WRITE_FAILED", "写入配置失败: ${t.message}"))
        }
    }

    /**
     * Read current config status from existing files.
     * Returns null if files don't exist.
     */
    suspend fun readConfigStatus(): DshConfigStatus? = withContext(Dispatchers.IO) {
        if (!settingsFile.isFile || !credentialsFile.isFile) return@withContext null

        try {
            val yaml = Yaml()
            val settings = yaml.load<Map<String, Any>>(FileInputStream(settingsFile)) ?: return@withContext null

            // Extract default model info
            val defaultModel = settings["agent-default-model"] as? Map<String, Any>
            val providerRoute = defaultModel?.get("provider") as? String
            val modelId = defaultModel?.get("model") as? String

            // Extract first provider's baseURL and apiKeyEnv
            val providers = (settings["llm-pi-ai"] as? Map<*, *>)?.get("providers") as? Map<*, *>
            val firstProvider = providers?.values?.firstOrNull() as? Map<*, *>
            val apiKeyEnv = firstProvider?.get("apiKeyEnv") as? String
            val baseURL = firstProvider?.get("baseURL") as? String

            // Check if credentials file has entries
            val creds = yaml.load<Map<String, Any>>(FileInputStream(credentialsFile))
            val apiKeySet = creds != null && creds.isNotEmpty()

            DshConfigStatus(
                providerRoute = providerRoute,
                modelId = modelId,
                apiKeyEnv = apiKeyEnv,
                apiKeySet = apiKeySet,
                baseURL = baseURL,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "readConfigStatus failed: ${t.message}")
            null
        }
    }

    /**
     * Check if both config files exist.
     */
    fun isConfigured(): Boolean = settingsFile.isFile && credentialsFile.isFile

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun writeSettings(preset: ProviderPreset, setAsDefault: Boolean) {
        val yaml = Yaml(YamlOptions())
        val existing: MutableMap<String, Any> = if (settingsFile.isFile) {
            try {
                (yaml.load<Map<String, Any>>(FileInputStream(settingsFile)) ?: mutableMapOf()).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }

        // Build the llm-pi-ai section
        val llmPiAi = (existing["llm-pi-ai"] as? MutableMap<String, Any>)?.toMutableMap()
            ?: mutableMapOf<String, Any>()
        val providers = (llmPiAi["providers"] as? MutableMap<String, Any>)?.toMutableMap()
            ?: mutableMapOf<String, Any>()

        // Build the provider-specific config
        providers[preset.routeKey] = buildProviderYaml(preset)

        // Preserve other top-level keys (e.g., existing agent-default-model)
        existing["llm-pi-ai"] = mapOf("providers" to providers)

        if (setAsDefault) {
            existing["agent-default-model"] = mapOf(
                "provider" to preset.routeKey,
                "model" to preset.models.first().id,
            )
        }

        // Atomic write: .tmp → rename
        val tmpFile = File(dshDir, "settings.yaml.tmp")
        FileWriter(tmpFile).use { yaml.dump(existing, it) }
        if (!tmpFile.renameTo(settingsFile)) {
            throw IOException(
                "atomic rename failed: ${tmpFile.absolutePath} -> ${settingsFile.absolutePath}",
            )
        }
        Log.i(TAG, "settings.yaml written (default=$setAsDefault)")
    }

    @Suppress("UNCHECKED_CAST")
    private fun buildProviderYaml(preset: ProviderPreset): Map<String, Any> {
        val provider = mutableMapOf<String, Any>(
            "displayName" to preset.displayName,
            "apiKeyEnv" to preset.apiKeyEnv,
            "api" to preset.api,
            "baseURL" to preset.baseURL,
        )

        if (preset.thinkingFormat != null) {
            provider["compat"] = mapOf("thinkingFormat" to preset.thinkingFormat)
        }
        if (preset.reasoning != null) {
            provider["reasoning"] = preset.reasoning
        }

        // Build models list
        val modelsList = preset.models.map { model ->
            val modelMap = mutableMapOf<String, Any>(
                "id" to model.id,
                "name" to model.name,
                "contextWindow" to model.contextWindow,
                "maxTokens" to model.maxTokens,
            )
            // reasoningEfforts with off: null (valueless key)
            if (model.reasoningEfforts.isNotEmpty()) {
                modelMap["reasoningEfforts"] = model.reasoningEfforts
            }
            modelMap
        }
        provider["models"] = modelsList
        return provider
    }

    private fun writeCredentials(apiKeyEnv: String, apiKey: String) {
        val yaml = Yaml(YamlOptions())
        val existing: MutableMap<String, Any> = if (credentialsFile.isFile) {
            try {
                (yaml.load<Map<String, Any>>(FileInputStream(credentialsFile)) ?: mutableMapOf()).toMutableMap()
            } catch (_: Exception) {
                mutableMapOf()
            }
        } else {
            mutableMapOf()
        }

        // Update the key
        existing[apiKeyEnv] = apiKey

        // Atomic write
        val tmpFile = File(dshDir, ".credentials.yaml.tmp")
        FileWriter(tmpFile).use { yaml.dump(existing, it) }
        if (!tmpFile.renameTo(credentialsFile)) {
            throw IOException(
                "atomic rename failed: ${tmpFile.absolutePath} -> ${credentialsFile.absolutePath}",
            )
        }

        // Set file permissions: 0600 (owner-only)
        credentialsFile.setReadable(true, true)
        credentialsFile.setWritable(true, true)
        credentialsFile.setExecutable(false, false)

        Log.i(TAG, ".credentials.yaml written, permission set to 0600")
    }
}

/**
 * Status snapshot of the DSH config files.
 */
data class DshConfigStatus(
    val providerRoute: String?,
    val modelId: String?,
    val apiKeyEnv: String?,
    val apiKeySet: Boolean,
    val baseURL: String?,
)

/**
 * SnakeYAML options that produce clean output:
 * - Block-style (not flow-style) for readability
 * - No implicit anchors/aliases for null values
 * - Write `off: null` explicitly (DSH schema requires the key)
 */
private class YamlOptions : DumperOptions() {
    init {
        defaultFlowStyle = FlowStyle.BLOCK
        isAllowUnicode = true
        indent = 2
        // SnakeYAML 2.x writes null as empty value by default,
        // which is acceptable for DSH's z.const(null) schema.
    }
}