package com.codesage.github;

import java.util.UUID;

import org.springframework.stereotype.Service;

import com.codesage.common.TokenCipher;
import com.codesage.user.GitHubConnection;
import com.codesage.user.GitHubConnectionRepository;

/** Server-side only access to the user's GitHub token. Never expose the result to API responses. */
@Service
public class GitHubTokenService {

    private final GitHubConnectionRepository connections;
    private final TokenCipher cipher;

    public GitHubTokenService(GitHubConnectionRepository connections, TokenCipher cipher) {
        this.connections = connections;
        this.cipher = cipher;
    }

    public String requireToken(UUID userId) {
        GitHubConnection connection = connections.findByUserId(userId)
                .filter(c -> !c.isRevoked())
                .orElseThrow(GitHubApiException::reauth);
        String token = cipher.decrypt(connection.getAccessTokenEncrypted());
        if (token == null || token.isBlank()) {
            throw GitHubApiException.reauth();
        }
        return token;
    }
}
