package com.codesage.ai;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.codesage.common.TokenCipher;
import com.codesage.config.AppProperties;
import com.codesage.settings.UserSettings;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Resolves which provider/model/key a review should use. A user-supplied key (stored encrypted) takes
 * precedence; otherwise the server-wide key from the environment is used for the server's provider.
 */
@Component
public class LlmProviderFactory {

    public record ResolvedLlm(LlmProvider provider, String model, String apiKey, String baseUrl, String keySource) {
    }

    public record ProviderInfo(String id, String label, String defaultModel, List<String> suggestedModels) {
    }

    public static final List<ProviderInfo> PROVIDERS = List.of(
            new ProviderInfo("anthropic", "Anthropic Claude", "claude-sonnet-5-5",
                    List.of("claude-sonnet-5-5", "claude-opus-5-5", "claude-haiku-5-5")),
            new ProviderInfo("openai", "OpenAI", "gpt-5", List.of("gpt-5", "gpt-5-mini")),
            new ProviderInfo("gemini", "Google Gemini (OpenAI-compatible endpoint)", "gemini-2.5-pro",
                    List.of("gemini-2.5-pro", "gemini-2.5-flash")),
            new ProviderInfo("openai-compatible", "OpenAI-compatible endpoint (server AI_BASE_URL)", "", List.of()));

    private final Map<String, LlmProvider> providers = new LinkedHashMap<>();
    private final AppProperties props;
    private final TokenCipher cipher;

    public LlmProviderFactory(AnthropicProvider anthropic, ObjectMapper mapper, AppProperties props, TokenCipher cipher) {
        this.props = props;
        this.cipher = cipher;
        providers.put("anthropic", anthropic);
        providers.put("openai", new OpenAiCompatibleProvider("openai", "https://api.openai.com/v1", mapper));
        providers.put("gemini", new OpenAiCompatibleProvider("gemini",
                "https://generativelanguage.googleapis.com/v1beta/openai", mapper));
        providers.put("openai-compatible", new OpenAiCompatibleProvider("openai-compatible", "", mapper));
    }

    /** Visible for tests: replace a provider implementation (e.g. with a stub). */
    public void register(String id, LlmProvider provider) {
        providers.put(id, provider);
    }

    public boolean isSupported(String providerId) {
        return providers.containsKey(providerId);
    }

    public boolean serverDefaultConfigured() {
        return props.ai().apiKey() != null && !props.ai().apiKey().isBlank() && providers.containsKey(props.ai().provider());
    }

    public Optional<ResolvedLlm> resolve(UserSettings settings) {
        String serverProvider = props.ai().provider();
        String providerId = settings != null && settings.getAiProvider() != null ? settings.getAiProvider() : serverProvider;
        LlmProvider provider = providers.get(providerId);
        if (provider == null) {
            return Optional.empty();
        }
        boolean sameAsServer = providerId.equals(serverProvider);
        String userKey = settings == null ? null : cipher.decrypt(settings.getAiApiKeyEncrypted());
        String apiKey;
        String source;
        if (userKey != null && !userKey.isBlank()) {
            apiKey = userKey;
            source = "user";
        } else if (sameAsServer && props.ai().apiKey() != null && !props.ai().apiKey().isBlank()) {
            apiKey = props.ai().apiKey();
            source = "server";
        } else {
            return Optional.empty();
        }
        // Custom base URLs are only accepted from server configuration (prevents SSRF via user settings).
        String baseUrl = sameAsServer ? props.ai().baseUrl() : null;
        if ("openai-compatible".equals(providerId) && (baseUrl == null || baseUrl.isBlank())) {
            return Optional.empty();
        }
        String model = settings != null && settings.getAiModel() != null && !settings.getAiModel().isBlank()
                ? settings.getAiModel()
                : sameAsServer ? props.ai().model() : defaultModel(providerId);
        return Optional.of(new ResolvedLlm(provider, model, apiKey, baseUrl, source));
    }

    public static String defaultModel(String providerId) {
        return PROVIDERS.stream().filter(p -> p.id().equals(providerId)).map(ProviderInfo::defaultModel)
                .findFirst().orElse("");
    }
}
