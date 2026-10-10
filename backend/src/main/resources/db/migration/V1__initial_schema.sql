-- CodeSage AI initial schema.
-- Written to be portable between PostgreSQL (production) and H2 in PostgreSQL mode (fast unit tests).

CREATE TABLE users (
    id              UUID PRIMARY KEY,
    github_id       BIGINT       NOT NULL,
    login           VARCHAR(100) NOT NULL,
    name            VARCHAR(255),
    email           VARCHAR(320),
    avatar_url      VARCHAR(1000),
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    last_login_at   TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_users_github_id UNIQUE (github_id)
);

CREATE TABLE github_connections (
    id                  UUID PRIMARY KEY,
    user_id             UUID         NOT NULL,
    access_token_enc    VARCHAR(4000),
    token_scopes        VARCHAR(500),
    connected_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at          TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked             BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_github_connections_user UNIQUE (user_id),
    CONSTRAINT fk_github_connections_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE user_settings (
    id                          UUID PRIMARY KEY,
    user_id                     UUID         NOT NULL,
    ai_provider                 VARCHAR(30),
    ai_model                    VARCHAR(100),
    ai_api_key_enc              VARCHAR(4000),
    ai_api_key_hint             VARCHAR(16),
    enabled_analyzers           VARCHAR(255) NOT NULL,
    enabled_languages           VARCHAR(500) NOT NULL,
    min_severity                VARCHAR(20)  NOT NULL,
    max_files                   INTEGER      NOT NULL,
    updated_at                  TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_user_settings_user UNIQUE (user_id),
    CONSTRAINT fk_user_settings_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE TABLE repositories (
    id               UUID PRIMARY KEY,
    user_id          UUID         NOT NULL,
    github_id        BIGINT       NOT NULL,
    owner            VARCHAR(100) NOT NULL,
    name             VARCHAR(200) NOT NULL,
    full_name        VARCHAR(301) NOT NULL,
    description      VARCHAR(2000),
    language         VARCHAR(100),
    private_repo     BOOLEAN      NOT NULL,
    default_branch   VARCHAR(255),
    html_url         VARCHAR(1000),
    auto_review      BOOLEAN      NOT NULL DEFAULT FALSE,
    pushed_at        TIMESTAMP WITH TIME ZONE,
    last_synced_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_repositories_user_github UNIQUE (user_id, github_id),
    CONSTRAINT fk_repositories_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);
CREATE INDEX idx_repositories_github_id ON repositories (github_id);
CREATE INDEX idx_repositories_user_name ON repositories (user_id, full_name);

CREATE TABLE pull_requests (
    id              UUID PRIMARY KEY,
    repository_id   UUID          NOT NULL,
    number          INTEGER       NOT NULL,
    title           VARCHAR(1000) NOT NULL,
    author          VARCHAR(100),
    state           VARCHAR(20),
    head_sha        VARCHAR(64),
    head_ref        VARCHAR(255),
    base_ref        VARCHAR(255),
    html_url        VARCHAR(1000),
    updated_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_pull_requests_repo_number UNIQUE (repository_id, number),
    CONSTRAINT fk_pull_requests_repo FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE
);

CREATE TABLE code_reviews (
    id                UUID PRIMARY KEY,
    user_id           UUID         NOT NULL,
    repository_id     UUID         NOT NULL,
    pull_request_id   UUID,
    review_type       VARCHAR(20)  NOT NULL,
    branch            VARCHAR(255),
    commit_sha        VARCHAR(64),
    status            VARCHAR(20)  NOT NULL,
    trigger_source    VARCHAR(20)  NOT NULL,
    score             INTEGER,
    summary           VARCHAR(8000),
    ai_status         VARCHAR(40),
    ai_provider       VARCHAR(30),
    ai_model          VARCHAR(100),
    files_considered  INTEGER      NOT NULL DEFAULT 0,
    files_analyzed    INTEGER      NOT NULL DEFAULT 0,
    files_skipped     INTEGER      NOT NULL DEFAULT 0,
    critical_count    INTEGER      NOT NULL DEFAULT 0,
    high_count        INTEGER      NOT NULL DEFAULT 0,
    medium_count      INTEGER      NOT NULL DEFAULT 0,
    low_count         INTEGER      NOT NULL DEFAULT 0,
    info_count        INTEGER      NOT NULL DEFAULT 0,
    notes             VARCHAR(4000),
    error_message     VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    started_at        TIMESTAMP WITH TIME ZONE,
    completed_at      TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_code_reviews_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_code_reviews_repo FOREIGN KEY (repository_id) REFERENCES repositories (id) ON DELETE CASCADE,
    CONSTRAINT fk_code_reviews_pr FOREIGN KEY (pull_request_id) REFERENCES pull_requests (id) ON DELETE SET NULL
);
CREATE INDEX idx_code_reviews_user_created ON code_reviews (user_id, created_at);
CREATE INDEX idx_code_reviews_repo ON code_reviews (repository_id, created_at);
CREATE INDEX idx_code_reviews_status ON code_reviews (status);

CREATE TABLE review_jobs (
    id                UUID PRIMARY KEY,
    review_id         UUID         NOT NULL,
    idempotency_key   VARCHAR(300) NOT NULL,
    status            VARCHAR(20)  NOT NULL,
    attempts          INTEGER      NOT NULL DEFAULT 0,
    max_attempts      INTEGER      NOT NULL,
    next_attempt_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at         TIMESTAMP WITH TIME ZONE,
    last_error        VARCHAR(2000),
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_review_jobs_review UNIQUE (review_id),
    CONSTRAINT uk_review_jobs_idempotency UNIQUE (idempotency_key),
    CONSTRAINT fk_review_jobs_review FOREIGN KEY (review_id) REFERENCES code_reviews (id) ON DELETE CASCADE
);
CREATE INDEX idx_review_jobs_pending ON review_jobs (status, next_attempt_at);

CREATE TABLE review_findings (
    id               UUID PRIMARY KEY,
    review_id        UUID          NOT NULL,
    source           VARCHAR(20)   NOT NULL,
    analyzer         VARCHAR(50)   NOT NULL,
    verification     VARCHAR(30)   NOT NULL,
    category         VARCHAR(30)   NOT NULL,
    severity         VARCHAR(20)   NOT NULL,
    confidence       DOUBLE PRECISION NOT NULL,
    title            VARCHAR(300)  NOT NULL,
    file_path        VARCHAR(1000) NOT NULL,
    start_line       INTEGER,
    end_line         INTEGER,
    description      VARCHAR(8000) NOT NULL,
    impact           VARCHAR(4000),
    recommendation   VARCHAR(4000),
    suggested_code   VARCHAR(8000),
    original_code    VARCHAR(8000),
    rule_id          VARCHAR(120),
    fingerprint      VARCHAR(64)   NOT NULL,
    status           VARCHAR(20)   NOT NULL,
    in_diff          BOOLEAN       NOT NULL DEFAULT FALSE,
    published_at     TIMESTAMP WITH TIME ZONE,
    created_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_review_findings_review FOREIGN KEY (review_id) REFERENCES code_reviews (id) ON DELETE CASCADE
);
CREATE INDEX idx_review_findings_review ON review_findings (review_id, severity);
CREATE INDEX idx_review_findings_fingerprint ON review_findings (fingerprint);

-- Metadata about which files a review looked at (paths only, no source code).
CREATE TABLE review_files (
    id              UUID PRIMARY KEY,
    review_id       UUID          NOT NULL,
    path            VARCHAR(1000) NOT NULL,
    language        VARCHAR(30),
    size_bytes      INTEGER,
    analyzed        BOOLEAN       NOT NULL,
    skip_reason     VARCHAR(200),
    changed_lines   VARCHAR(4000),
    CONSTRAINT fk_review_files_review FOREIGN KEY (review_id) REFERENCES code_reviews (id) ON DELETE CASCADE
);
CREATE INDEX idx_review_files_review ON review_files (review_id);

CREATE TABLE webhook_events (
    id                      UUID PRIMARY KEY,
    delivery_id             VARCHAR(100) NOT NULL,
    event_type              VARCHAR(60)  NOT NULL,
    action                  VARCHAR(60),
    repository_github_id    BIGINT,
    repository_full_name    VARCHAR(301),
    pr_number               INTEGER,
    head_sha                VARCHAR(64),
    status                  VARCHAR(20)  NOT NULL,
    reviews_queued          INTEGER      NOT NULL DEFAULT 0,
    error                   VARCHAR(2000),
    received_at             TIMESTAMP WITH TIME ZONE NOT NULL,
    processed_at            TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_webhook_events_delivery UNIQUE (delivery_id)
);
