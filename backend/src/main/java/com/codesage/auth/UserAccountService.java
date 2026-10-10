package com.codesage.auth;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.codesage.common.TokenCipher;
import com.codesage.exception.ApiException;
import com.codesage.user.GitHubConnection;
import com.codesage.user.GitHubConnectionRepository;
import com.codesage.user.User;
import com.codesage.user.UserRepository;

@Service
public class UserAccountService {

    private final UserRepository users;
    private final GitHubConnectionRepository connections;
    private final TokenCipher cipher;

    public UserAccountService(UserRepository users, GitHubConnectionRepository connections, TokenCipher cipher) {
        this.users = users;
        this.connections = connections;
        this.cipher = cipher;
    }

    @Transactional
    public UUID upsertOnLogin(long githubId, String login, String name, String email, String avatarUrl,
                              String accessToken, String scopes) {
        User user = users.findByGithubId(githubId).orElseGet(User::new);
        user.setGithubId(githubId);
        user.setLogin(login == null ? "unknown" : login);
        user.setName(name);
        user.setEmail(email);
        user.setAvatarUrl(avatarUrl);
        user.setLastLoginAt(Instant.now());
        user = users.save(user);

        UUID userId = user.getId();
        GitHubConnection connection = connections.findByUserId(userId).orElseGet(GitHubConnection::new);
        connection.setUserId(userId);
        connection.setAccessTokenEncrypted(cipher.encrypt(accessToken));
        connection.setTokenScopes(scopes);
        connection.setConnectedAt(Instant.now());
        connection.setRevoked(false);
        connections.save(connection);
        return userId;
    }

    @Transactional(readOnly = true)
    public User require(UUID userId) {
        return users.findById(userId).orElseThrow(() -> new ApiException(
                org.springframework.http.HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Account no longer exists"));
    }

    /** Removes the stored GitHub token. Review history is kept unless deleteData is true. */
    @Transactional
    public void disconnect(UUID userId, boolean deleteData) {
        connections.findByUserId(userId).ifPresent(c -> {
            c.setAccessTokenEncrypted(null);
            c.setRevoked(true);
            connections.save(c);
        });
        if (deleteData) {
            // Cascading foreign keys remove repositories, reviews, findings, jobs and settings.
            users.deleteById(userId);
        }
    }
}
