package com.codesage.auth;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.codesage.ai.LlmProviderFactory;
import com.codesage.config.AppProperties;
import com.codesage.security.CurrentUser;
import com.codesage.security.SecurityConfig;
import com.codesage.user.GitHubConnection;
import com.codesage.user.GitHubConnectionRepository;
import com.codesage.user.User;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
public class AuthController {

    private final AppProperties props;
    private final CurrentUser currentUser;
    private final UserAccountService accounts;
    private final GitHubConnectionRepository connections;
    private final LlmProviderFactory llm;

    public AuthController(AppProperties props, CurrentUser currentUser, UserAccountService accounts,
                          GitHubConnectionRepository connections, LlmProviderFactory llm) {
        this.props = props;
        this.currentUser = currentUser;
        this.accounts = accounts;
        this.connections = connections;
        this.llm = llm;
    }

    @Operation(summary = "Public integration status so the UI can show setup guidance instead of failing silently")
    @GetMapping("/config")
    public AuthDtos.AuthConfigResponse config() {
        return new AuthDtos.AuthConfigResponse(
                props.github().oauthConfigured(),
                llm.serverDefaultConfigured(),
                props.ai().provider(),
                props.github().webhookConfigured());
    }

    @Operation(summary = "Start GitHub OAuth (redirects to GitHub). State/CSRF is handled by Spring Security.")
    @GetMapping("/github")
    public void startGitHubLogin(HttpServletResponse response) throws IOException {
        if (!props.github().oauthConfigured()) {
            response.sendRedirect(props.frontendUrl() + "/login?error=github_not_configured");
            return;
        }
        // Absolute URL on the public origin, so the redirect never depends on proxy Host headers.
        String base = props.publicUrl().endsWith("/") ? props.publicUrl().substring(0, props.publicUrl().length() - 1)
                : props.publicUrl();
        response.sendRedirect(base + "/oauth2/authorization/" + SecurityConfig.REGISTRATION_ID);
    }

    @Operation(summary = "Current user profile and GitHub connection status (never returns tokens)")
    @GetMapping("/me")
    public AuthDtos.MeResponse me() {
        UUID userId = currentUser.requireId();
        User user = accounts.require(userId);
        GitHubConnection conn = connections.findByUserId(userId).orElse(null);
        boolean connected = conn != null && !conn.isRevoked() && conn.getAccessTokenEncrypted() != null;
        return new AuthDtos.MeResponse(user.getId(), user.getLogin(), user.getName(), user.getEmail(),
                user.getAvatarUrl(), connected, conn == null ? null : conn.getTokenScopes(),
                conn == null ? null : conn.getConnectedAt());
    }

    @Operation(summary = "Disconnect GitHub (deletes the stored token) and optionally delete all CodeSage data")
    @DeleteMapping("/account")
    public ResponseEntity<Void> disconnect(@RequestParam(defaultValue = "false") boolean deleteData,
                                           HttpServletRequest request) throws ServletException {
        UUID userId = currentUser.requireId();
        accounts.disconnect(userId, deleteData);
        request.logout();
        if (request.getSession(false) != null) {
            request.getSession(false).invalidate();
        }
        return ResponseEntity.noContent().build();
    }
}
