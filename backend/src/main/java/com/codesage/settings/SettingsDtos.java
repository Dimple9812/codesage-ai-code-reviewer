package com.codesage.settings;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class SettingsDtos {

    private SettingsDtos() {
    }

    public record Option(String id, String label, boolean available) {
    }

    public record ProviderOption(String id, String label, String defaultModel, List<String> suggestedModels) {
    }

    public record SettingsResponse(
            String aiProvider,
            String aiModel,
            boolean aiKeyConfigured,
            String aiKeyHint,
            String aiKeySource,
            boolean serverAiConfigured,
            String serverAiProvider,
            List<ProviderOption> providers,
            List<String> enabledAnalyzers,
            List<Option> availableAnalyzers,
            List<String> enabledLanguages,
            List<Option> availableLanguages,
            String minSeverity,
            int maxFiles,
            int maxFilesLimit,
            String dataRetention) {
    }

    public record UpdateSettingsRequest(
            @NotBlank @Pattern(regexp = "anthropic|openai|gemini|openai-compatible") String aiProvider,
            @Size(max = 100) @Pattern(regexp = "^[A-Za-z0-9._:/@-]*$", message = "contains invalid characters") String aiModel,
            @NotNull @Size(max = 10) List<@Pattern(regexp = "ai|pmd|rules|semgrep") String> enabledAnalyzers,
            @NotNull @Size(min = 1, max = 20) List<@Pattern(regexp = "[a-z]+") String> enabledLanguages,
            @NotBlank @Pattern(regexp = "CRITICAL|HIGH|MEDIUM|LOW|INFO") String minSeverity,
            @Min(1) @Max(500) int maxFiles) {
    }

    public record ApiKeyRequest(@NotBlank @Size(min = 10, max = 400) String apiKey) {
    }
}
