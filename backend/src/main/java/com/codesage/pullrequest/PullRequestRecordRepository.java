package com.codesage.pullrequest;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestRecordRepository extends JpaRepository<PullRequestRecord, UUID> {

    Optional<PullRequestRecord> findByRepositoryIdAndNumber(UUID repositoryId, int number);
}
