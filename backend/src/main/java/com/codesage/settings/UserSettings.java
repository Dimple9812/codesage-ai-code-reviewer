package com.codesage.settings;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "ai_api_key_enc")
    private String aiApiKeyEncrypted;

    @Column(name = "ai_api_key_hint")
    private String aiApiKeyHint;

    /** Comma separated analyzer ids, e.g. "ai,pmd,rules". */
    @Column(name = "enabled_analyzers", nullable = false)
    private String enabledAnalyzers;

    /** Comma separated language ids. */
    @Column(name = "enabled_languages", nullable = false)
    private String enabledLanguages;

    @Column(name = "min_severity", nullable = false)
    private String minSeverity;

    @Column(name = "max_files", nullable = false)
    private int maxFiles;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String aiProvider) { this.aiProvider = aiProvider; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public String getAiApiKeyEncrypted() { return aiApiKeyEncrypted; }
    public void setAiApiKeyEncrypted(String v) { this.aiApiKeyEncrypted = v; }
    public String getAiApiKeyHint() { return aiApiKeyHint; }
    public void setAiApiKeyHint(String aiApiKeyHint) { this.aiApiKeyHint = aiApiKeyHint; }
    public String getEnabledAnalyzers() { return enabledAnalyzers; }
    public void setEnabledAnalyzers(String enabledAnalyzers) { this.enabledAnalyzers = enabledAnalyzers; }
    public String getEnabledLanguages() { return enabledLanguages; }
    public void setEnabledLanguages(String enabledLanguages) { this.enabledLanguages = enabledLanguages; }
    public String getMinSeverity() { return minSeverity; }
    public void setMinSeverity(String minSeverity) { this.minSeverity = minSeverity; }
    public int getMaxFiles() { return maxFiles; }
    public void setMaxFiles(int maxFiles) { this.maxFiles = maxFiles; }
    public Instant getUpdatedAt() { return updatedAt; }
}
