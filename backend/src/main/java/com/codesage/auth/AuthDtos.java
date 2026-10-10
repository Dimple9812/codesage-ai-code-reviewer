package com.codesage.auth;

import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record MeResponse(UUID id, String login, String name, String email, String avatarUrl,
                             boolean githubConnected, String githubScopes, Instant connectedAt) {
    }

    public record AuthConfigResponse(boolean githubConfigured, boolean aiConfigured, String aiProvider,
                                     boolean webhooksConfigured) {
    }
}
