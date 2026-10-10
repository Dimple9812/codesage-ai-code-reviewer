package com.codesage.review;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "review_files")
public class ReviewFile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "review_id", nullable = false)
    private UUID reviewId;

    @Column(nullable = false)
    private String path;

    private String language;

    @Column(name = "size_bytes")
    private Integer sizeBytes;

    @Column(nullable = false)
    private boolean analyzed;

    @Column(name = "skip_reason")
    private String skipReason;

    /** Compact line ranges for PR reviews, e.g. "3-7,12". */
    @Column(name = "changed_lines")
    private String changedLines;

    public UUID getId() { return id; }
    public UUID getReviewId() { return reviewId; }
    public void setReviewId(UUID reviewId) { this.reviewId = reviewId; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }
    public Integer getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(Integer sizeBytes) { this.sizeBytes = sizeBytes; }
    public boolean isAnalyzed() { return analyzed; }
    public void setAnalyzed(boolean analyzed) { this.analyzed = analyzed; }
    public String getSkipReason() { return skipReason; }
    public void setSkipReason(String skipReason) { this.skipReason = skipReason; }
    public String getChangedLines() { return changedLines; }
    public void setChangedLines(String changedLines) { this.changedLines = changedLines; }
}
