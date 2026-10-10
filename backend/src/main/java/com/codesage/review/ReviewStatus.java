package com.codesage.review;

public enum ReviewStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }
}
