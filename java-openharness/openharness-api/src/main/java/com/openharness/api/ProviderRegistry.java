package com.openharness.api;

import com.openharness.config.ProviderProfile;
import com.openharness.config.Settings;

import java.util.List;
import java.util.Optional;

/**
 * Single source of truth for LLM provider metadata.
 * Java equivalent of Python's registry.py PROVIDERS tuple.
 * <p>
 * Adding a new provider: add a ProviderSpec to PROVIDERS below. Done.
 */
public final class ProviderRegistry {

    private ProviderRegistry() {}

    /**
     * Ordered list of all known provider specs — order controls detection priority.
     */
    public static final List<ProviderSpec> PROVIDERS = List.of(
            // ── GitHub Copilot (OAuth) ──
            new ProviderSpec("github_copilot",
                    new String[]{"copilot"}, "",
                    "GitHub Copilot", "copilot", "",
                    "", "", false, false, true),

            // ── Gateways ──
            new ProviderSpec("openrouter",
                    new String[]{"openrouter"}, "OPENROUTER_API_KEY",
                    "OpenRouter", "openai_compat", "https://openrouter.ai/api/v1",
                    "sk-or-", "openrouter", true, false, false),

            new ProviderSpec("aihubmix",
                    new String[]{"aihubmix"}, "OPENAI_API_KEY",
                    "AiHubMix", "openai_compat", "https://aihubmix.com/v1",
                    "", "aihubmix", true, false, false),

            new ProviderSpec("siliconflow",
                    new String[]{"siliconflow"}, "OPENAI_API_KEY",
                    "SiliconFlow", "openai_compat", "https://api.siliconflow.cn/v1",
                    "", "siliconflow", true, false, false),

            new ProviderSpec("volcengine",
                    new String[]{"volcengine", "volces", "ark"}, "OPENAI_API_KEY",
                    "VolcEngine", "openai_compat", "https://ark.cn-beijing.volces.com/api/v3",
                    "", "volces", true, false, false),

            new ProviderSpec("modelscope",
                    new String[]{"modelscope"}, "MODELSCOPE_API_KEY",
                    "ModelScope", "openai_compat", "https://api-inference.modelscope.cn/v1",
                    "", "modelscope", false, false, false),

            // ── Standard cloud providers ──
            new ProviderSpec("anthropic",
                    new String[]{"anthropic", "claude"}, "ANTHROPIC_API_KEY",
                    "Anthropic", "anthropic", "",
                    "", "", false, false, false),

            new ProviderSpec("openai",
                    new String[]{"openai", "gpt", "o1", "o3", "o4"}, "OPENAI_API_KEY",
                    "OpenAI", "openai_compat", "",
                    "", "", false, false, false),

            new ProviderSpec("deepseek",
                    new String[]{"deepseek"}, "DEEPSEEK_API_KEY",
                    "DeepSeek", "openai_compat", "https://api.deepseek.com/v1",
                    "", "deepseek", false, false, false),

            new ProviderSpec("gemini",
                    new String[]{"gemini"}, "GEMINI_API_KEY",
                    "Gemini", "openai_compat", "https://generativelanguage.googleapis.com/v1beta/openai",
                    "", "googleapis", false, false, false),

            new ProviderSpec("dashscope",
                    new String[]{"qwen", "dashscope"}, "DASHSCOPE_API_KEY",
                    "DashScope", "openai_compat", "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    "", "dashscope", false, false, false),

            new ProviderSpec("moonshot",
                    new String[]{"moonshot", "kimi"}, "MOONSHOT_API_KEY",
                    "Moonshot", "openai_compat", "https://api.moonshot.ai/v1",
                    "", "moonshot", false, false, false),

            new ProviderSpec("minimax",
                    new String[]{"minimax"}, "MINIMAX_API_KEY",
                    "MiniMax", "openai_compat", "https://api.minimax.io/v1",
                    "", "minimax", false, false, false),

            new ProviderSpec("zhipu",
                    new String[]{"zhipu", "glm", "chatglm"}, "ZHIPUAI_API_KEY",
                    "Zhipu AI", "openai_compat", "https://open.bigmodel.cn/api/paas/v4",
                    "", "bigmodel", false, false, false),

            new ProviderSpec("groq",
                    new String[]{"groq"}, "GROQ_API_KEY",
                    "Groq", "openai_compat", "https://api.groq.com/openai/v1",
                    "gsk_", "groq", false, false, false),

            new ProviderSpec("mistral",
                    new String[]{"mistral", "mixtral", "codestral"}, "MISTRAL_API_KEY",
                    "Mistral", "openai_compat", "https://api.mistral.ai/v1",
                    "", "mistral", false, false, false),

            new ProviderSpec("stepfun",
                    new String[]{"step-", "stepfun"}, "STEPFUN_API_KEY",
                    "StepFun", "openai_compat", "https://api.stepfun.com/v1",
                    "", "stepfun", false, false, false),

            new ProviderSpec("baidu",
                    new String[]{"ernie", "baidu"}, "QIANFAN_ACCESS_KEY",
                    "Baidu", "openai_compat", "https://qianfan.baidubce.com/v2",
                    "", "baidubce", false, false, false),

            // ── Cloud platforms ──
            new ProviderSpec("bedrock",
                    new String[]{"bedrock"}, "AWS_ACCESS_KEY_ID",
                    "AWS Bedrock", "openai_compat", "",
                    "", "bedrock", false, false, false),

            new ProviderSpec("vertex",
                    new String[]{"vertex"}, "GOOGLE_APPLICATION_CREDENTIALS",
                    "Vertex AI", "openai_compat", "",
                    "", "aiplatform", false, false, false),

            // ── Local deployments ──
            new ProviderSpec("ollama",
                    new String[]{"ollama"}, "",
                    "Ollama", "openai_compat", "http://localhost:11434/v1",
                    "", "localhost:11434", false, true, false),

            new ProviderSpec("vllm",
                    new String[]{"vllm"}, "",
                    "vLLM/Local", "openai_compat", "",
                    "", "", false, true, false)
    );

    /**
     * Find a provider spec by canonical name.
     */
    public static Optional<ProviderSpec> findByName(String name) {
        return PROVIDERS.stream()
                .filter(s -> s.name().equals(name))
                .findFirst();
    }

    /**
     * Detect the best-matching ProviderSpec for the given inputs.
     * Priority: api_key prefix → base_url keyword → model name keyword
     */
    public static Optional<ProviderSpec> detect(String model, String apiKey, String baseUrl) {
        // 1. api_key prefix
        if (apiKey != null && !apiKey.isBlank()) {
            for (ProviderSpec spec : PROVIDERS) {
                String prefix = spec.detectByKeyPrefix();
                if (!prefix.isEmpty() && apiKey.startsWith(prefix)) {
                    return Optional.of(spec);
                }
            }
        }

        // 2. base_url keyword
        if (baseUrl != null && !baseUrl.isBlank()) {
            String baseLower = baseUrl.toLowerCase();
            for (ProviderSpec spec : PROVIDERS) {
                String kw = spec.detectByBaseKeyword();
                if (!kw.isEmpty() && baseLower.contains(kw)) {
                    return Optional.of(spec);
                }
            }
        }

        // 3. model keyword
        if (model != null && !model.isBlank()) {
            return matchByModel(model);
        }

        return Optional.empty();
    }

    private static Optional<ProviderSpec> matchByModel(String model) {
        String modelLower = model.toLowerCase();
        String modelNormalized = modelLower.replace("-", "_");
        String modelPrefix = modelLower.contains("/")
                ? modelLower.split("/", 1)[0]
                : "";
        String normalizedPrefix = modelPrefix.replace("-", "_");

        List<ProviderSpec> stdSpecs = PROVIDERS.stream()
                .filter(s -> !s.isLocal() && !s.isOauth())
                .toList();

        // Prefer explicit provider-prefix match
        if (!modelPrefix.isEmpty()) {
            for (ProviderSpec spec : stdSpecs) {
                if (normalizedPrefix.equals(spec.name())) {
                    return Optional.of(spec);
                }
            }
        }

        // Fall back to keyword scan
        for (ProviderSpec spec : stdSpecs) {
            for (String kw : spec.keywords()) {
                String kwNormalized = kw.replace("-", "_");
                if (modelLower.contains(kw) || modelNormalized.contains(kwNormalized)) {
                    return Optional.of(spec);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Resolve a provider spec from settings.
     */
    public static ProviderSpec resolve(Settings settings) {
        String profileName = settings.activeProfile();
        ProviderProfile profile = settings.mergedProfiles().get(profileName);

        if (profile != null) {
            Optional<ProviderSpec> found = findByName(profile.provider());
            if (found.isPresent()) return found.get();
        }

        return detect(settings.model(), settings.apiKey(), settings.baseUrl())
                .orElseGet(() -> findByName("anthropic").orElseThrow());
    }
}
