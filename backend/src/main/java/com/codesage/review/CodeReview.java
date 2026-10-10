package com.codesage.review;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "code_reviews")
public class CodeReview {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "repository_id", nullable = false)
    private UUID repositoryId;

    @Column(name = "pull_request_id")
    private UUID pullRequestId;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_type", nullable = false)
    private ReviewType type;

    private String branch;

    @Column(name = "commit_sha")
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReviewStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_source", nullable = false)
    private TriggerSource triggerSource;

    private Integer score;
    private String summary;

    @Enumerated(EnumType.STRING)
    @Column(name = "ai_status")
    private AiStatus aiStatus;

    @Column(name = "ai_provider")
    private String aiProvider;

    @Column(name = "ai_model")
    private String aiModel;

    @Column(name = "files_considered", nullable = false)
    private int filesConsidered;

    @Column(name = "files_analyzed", nullable = false)
    private int filesAnalyzed;

    @Column(name = "files_skipped", nullable = false)
    private int filesSkipped;

    @Column(name = "critical_count", nullable = false)
    private int criticalCount;

    @Column(name = "high_count", nullable = false)
    private int highCount;

    @Column(name = "medium_count", nullable = false)
    private int mediumCount;

    @Column(name = "low_count", nullable = false)
    private int lowCount;

    @Column(name = "info_count", nullable = false)
    private int infoCount;

    private String notes;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public int totalFindings() {
        return criticalCount + highCount + mediumCount + lowCount + infoCount;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getUserId() { return userId; }
    public void setUserId(UUID userId) { this.userId = userId; }
    public UUID getRepositoryId() { return repositoryId; }
    public void setRepositoryId(UUID repositoryId) { this.repositoryId = repositoryId; }
    public UUID getPullRequestId() { return pullRequestId; }
    public void setPullRequestId(UUID pullRequestId) { this.pullRequestId = pullRequestId; }
    public ReviewType getType() { return type; }
    public void setType(ReviewType type) { this.type = type; }
    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }
    public String getCommitSha() { return commitSha; }
    public void setCommitSha(String commitSha) { this.commitSha = commitSha; }
    public ReviewStatus getStatus() { return status; }
    public void setStatus(ReviewStatus status) { this.status = status; }
    public TriggerSource getTriggerSource() { return triggerSource; }
    public void setTriggerSource(TriggerSource triggerSource) { this.triggerSource = triggerSource; }
    public Integer getScore() { return score; }
    public void setScore(Integer score) { this.score = score; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public AiStatus getAiStatus() { return aiStatus; }
    public void setAiStatus(AiStatus aiStatus) { this.aiStatus = aiStatus; }
    public String getAiProvider() { return aiProvider; }
    public void setAiProvider(String aiProvider) { this.aiProvider = aiProvider; }
    public String getAiModel() { return aiModel; }
    public void setAiModel(String aiModel) { this.aiModel = aiModel; }
    public int getFilesConsidered() { return filesConsidered; }
    public void setFilesConsidered(int v) { this.filesConsidered = v; }
    public int getFilesAnalyzed() { return filesAnalyzed; }
    public void setFilesAnalyzed(int v) { this.filesAnalyzed = v; }
    public int getFilesSkipped() { return filesSkipped; }
    public void setFilesSkipped(int v) { this.filesSkipped = v; }
    public int getCriticalCount() { return criticalCount; }
    public void setCriticalCount(int v) { this.criticalCount = v; }
    public int getHighCount() { return highCount; }
    public void setHighCount(int v) { this.highCount = v; }
    public int getMediumCount() { return mediumCount; }
    public void setMediumCount(int v) { this.mediumCount = v; }
    public int getLowCount() { return lowCount; }
    public void setLowCount(int v) { this.lowCount = v; }
    public int getInfoCount() { return infoCount; }
    public void setInfoCount(int v) { this.infoCount = v; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }
}
