package com.codesage.settings;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codesage.ai.LlmProviderFactory;
import com.codesage.analysis.Language;
import com.codesage.analysis.StaticAnalyzer;
import com.codesage.common.TokenCipher;
import com.codesage.config.AppProperties;
import com.codesage.exception.ApiException;
import com.codesage.findings.Severity;
import com.codesage.settings.SettingsDtos.Option;
import com.codesage.settings.SettingsDtos.ProviderOption;
import com.codesage.settings.SettingsDtos.SettingsResponse;
import com.codesage.settings.SettingsDtos.UpdateSettingsRequest;

@Service
public class SettingsService {

    public static final String RETENTION_NOTICE = "CodeSage stores repository metadata, review results and short code "
            + "excerpts (at most 15 lines) attached to findings. Full source files are fetched from GitHub on demand, "
            + "processed in memory and never persisted. GitHub tokens and API keys are encrypted at rest (AES-256-GCM) "
            + "and never sent to the browser. Code sent for AI analysis is transmitted to the configured AI provider.";

    private final UserSettingsRepository repository;
    private final AppProperties props;
    private final TokenCipher cipher;
    private final LlmProviderFactory llm;
    private final List<StaticAnalyzer> analyzers;

    public SettingsService(UserSettingsRepository repository, AppProperties props, TokenCipher cipher,
                           LlmProviderFactory llm, List<StaticAnalyzer> analyzers) {
        this.repository = repository;
        this.props = props;
        this.cipher = cipher;
        this.llm = llm;
        this.analyzers = analyzers;
    }

    @Transactional
    public UserSettings getOrCreate(UUID userId) {
        return repository.findByUserId(userId).orElseGet(() -> {
            UserSettings s = new UserSettings();
            s.setUserId(userId);
            s.setEnabledAnalyzers(props.analysis().semgrepEnabled() ? "ai,pmd,rules,semgrep" : "ai,pmd,rules");
            s.setEnabledLanguages(Arrays.stream(Language.values()).map(Language::id).collect(Collectors.joining(",")));
            s.setMinSeverity(Severity.LOW.name());
            s.setMaxFiles(props.review().maxFiles());
            return repository.save(s);
        });
    }

    @Transactional
    public SettingsResponse get(UUID userId) {
        return toResponse(getOrCreate(userId));
    }

    @Transactional
    public SettingsResponse update(UUID userId, UpdateSettingsRequest request) {
        UserSettings s = getOrCreate(userId);
        if (!llm.isSupported(request.aiProvider())) {
            throw ApiException.badRequest("Unsupported AI provider");
        }
        Set<String> languageIds = Arrays.stream(Language.values()).map(Language::id).collect(Collectors.toSet());
        for (String l : request.enabledLanguages()) {
            if (!languageIds.contains(l)) {
                throw ApiException.badRequest("Unsupported language: " + l);
            }
        }
        s.setAiProvider(request.aiProvider());
        s.setAiModel(request.aiModel() == null || request.aiModel().isBlank() ? null : request.aiModel().trim());
        s.setEnabledAnalyzers(String.join(",", new LinkedHashSet<>(request.enabledAnalyzers())));
        s.setEnabledLanguages(String.join(",", new LinkedHashSet<>(request.enabledLanguages())));
        s.setMinSeverity(request.minSeverity());
        s.setMaxFiles(Math.min(request.maxFiles(), props.review().maxFiles()));
        return toResponse(repository.save(s));
    }

    @Transactional
    public SettingsResponse setApiKey(UUID userId, String apiKey) {
        UserSettings s = getOrCreate(userId);
        String trimmed = apiKey.trim();
        s.setAiApiKeyEncrypted(cipher.encrypt(trimmed));
        s.setAiApiKeyHint(trimmed.length() >= 8 ? trimmed.substring(trimmed.length() - 4) : null);
        return toResponse(repository.save(s));
    }

    @Transactional
    public SettingsResponse clearApiKey(UUID userId) {
        UserSettings s = getOrCreate(userId);
        s.setAiApiKeyEncrypted(null);
        s.setAiApiKeyHint(null);
        return toResponse(repository.save(s));
    }

    public static Set<String> csv(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(String::trim).filter(v -> !v.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    public static Set<Language> languages(UserSettings s) {
        return csv(s.getEnabledLanguages()).stream().map(Language::fromId).flatMap(java.util.Optional::stream)
                .collect(Collectors.toSet());
    }

    private SettingsResponse toResponse(UserSettings s) {
        var resolved = llm.resolve(s);
        String provider = s.getAiProvider() != null ? s.getAiProvider() : props.ai().provider();
        return new SettingsResponse(
                provider,
                resolved.map(LlmProviderFactory.ResolvedLlm::model).orElse(s.getAiModel()),
                resolved.isPresent(),
                s.getAiApiKeyHint() == null ? null : "••••" + s.getAiApiKeyHint(),
                resolved.map(LlmProviderFactory.ResolvedLlm::keySource).orElse("none"),
                llm.serverDefaultConfigured(),
                props.ai().provider(),
                LlmProviderFactory.PROVIDERS.stream()
                        .map(p -> new ProviderOption(p.id(), p.label(), p.defaultModel(), p.suggestedModels())).toList(),
                List.copyOf(csv(s.getEnabledAnalyzers())),
                List.of(new Option("ai", "AI review (LLM)", true),
                        option("pmd", "PMD (Java static analysis)"),
                        option("rules", "CodeSage security & config rules"),
                        option("semgrep", "Semgrep (requires CLI on server)")),
                List.copyOf(csv(s.getEnabledLanguages())),
                Arrays.stream(Language.values()).map(l -> new Option(l.id(), label(l), true)).toList(),
                s.getMinSeverity(),
                s.getMaxFiles(),
                props.review().maxFiles(),
                RETENTION_NOTICE);
    }

    private Option option(String id, String label) {
        boolean available = analyzers.stream().anyMatch(a -> a.id().equals(id) && a.isEnabledGlobally());
        return new Option(id, label, available);
    }

    private static String label(Language l) {
        return switch (l) {
            case JAVA -> "Java";
            case JAVASCRIPT -> "JavaScript";
            case TYPESCRIPT -> "TypeScript";
            case PYTHON -> "Python";
            case C -> "C";
            case CPP -> "C++";
            case GO -> "Go";
            case SQL -> "SQL";
            case JSON -> "JSON";
            case YAML -> "YAML";
            case CONFIG -> "Config (Dockerfile, .properties, .toml)";
        };
    }
}
