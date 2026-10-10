package com.codesage.user;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface GitHubConnectionRepository extends JpaRepository<GitHubConnection, UUID> {

    Optional<GitHubConnection> findByUserId(UUID userId);
}
