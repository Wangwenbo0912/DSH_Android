package com.dshbox.app.config

/**
 * Pre-configured LLM provider template for DSH settings.yaml.
 * Maps directly to a `llm-pi-ai.providers.<routeKey>` entry.
 */
enum class ProviderPreset(
    val routeKey: String,
    val displayName: String,
    val apiKeyEnv: String,
    val baseURL: String,
    val api: String,              // "openai-completions"
    val thinkingFormat: String?,  // "deepseek" for DeepSeek, null for GLM
    val reasoning: String?,       // "high" for DeepSeek, null for GLM
    val models: List<ModelPreset>,
) {
    KABUAI_DEEPSEEK(
        routeKey = "kabuai",
        displayName = "KabuAI 中转（DeepSeek-V4-Flash）",
        apiKeyEnv = "KABUAI_API_KEY",
        baseURL = "https://api.kabuai.cn/v1",
        api = "openai-completions",
        thinkingFormat = "deepseek",
        reasoning = "high",
        models = listOf(
            ModelPreset(
                id = "deepseek-v4-flash",
                name = "DeepSeek-V4-Flash",
                contextWindow = 1_000_000,
                maxTokens = 256_000,
                reasoningEfforts = mapOf("off" to null, "high" to "high", "max" to "max"),
            ),
        ),
    ),
    KABUAI_GLM(
        routeKey = "kabuai-glm",
        displayName = "KabuAI 中转（GLM-5.2）",
        apiKeyEnv = "KABUAI_GLM_API_KEY",
        baseURL = "https://api.kabuai.cn/v1",
        api = "openai-completions",
        thinkingFormat = null,
        reasoning = null,
        models = listOf(
            ModelPreset(
                id = "glm-5.2",
                name = "GLM-5.2",
                contextWindow = 128_000,
                maxTokens = 4_096,
                reasoningEfforts = mapOf("off" to null, "high" to "high"),
            ),
        ),
    ),
}

data class ModelPreset(
    val id: String,
    val name: String,
    val contextWindow: Int,
    val maxTokens: Int,
    val reasoningEfforts: Map<String, String?>,  // null value = valueless key (off:)
)