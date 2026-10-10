package com.codesage.webhook;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

@Entity
@Table(name = "webhook_events")
public class WebhookEvent {

    public enum Status { RECEIVED, PROCESSED, IGNORED, FAILED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "delivery_id", nullable = false, unique = true)
    private String deliveryId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    private String action;

    @Column(name = "repository_github_id")
    private Long repositoryGithubId;

    @Column(name = "repository_full_name")
    private String repositoryFullName;

    @Column(name = "pr_number")
    private Integer prNumber;

    @Column(name = "head_sha")
    private String headSha;

    @jakarta.persistence.Enumerated(jakarta.persistence.EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(name = "reviews_queued", nullable = false)
    private int reviewsQueued;

    private String error;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "processed_at")
    private Instant processedAt;

    @PrePersist
    void prePersist() {
        receivedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getDeliveryId() { return deliveryId; }
    public void setDeliveryId(String deliveryId) { this.deliveryId = deliveryId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public Long getRepositoryGithubId() { return repositoryGithubId; }
    public void setRepositoryGithubId(Long v) { this.repositoryGithubId = v; }
    public String getRepositoryFullName() { return repositoryFullName; }
    public void setRepositoryFullName(String v) { this.repositoryFullName = v; }
    public Integer getPrNumber() { return prNumber; }
    public void setPrNumber(Integer prNumber) { this.prNumber = prNumber; }
    public String getHeadSha() { return headSha; }
    public void setHeadSha(String headSha) { this.headSha = headSha; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public int getReviewsQueued() { return reviewsQueued; }
    public void setReviewsQueued(int reviewsQueued) { this.reviewsQueued = reviewsQueued; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public Instant getReceivedAt() { return receivedAt; }
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
}
