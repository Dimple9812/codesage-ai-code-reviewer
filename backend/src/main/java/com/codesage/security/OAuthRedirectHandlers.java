package com.codesage.security;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** After GitHub authorisation, send the browser back to the SPA (never with tokens in the URL). */
final class OAuthRedirectHandlers {

    private static final Logger log = LoggerFactory.getLogger(OAuthRedirectHandlers.class);

    private OAuthRedirectHandlers() {
    }

    static final class Success implements AuthenticationSuccessHandler {
        private final String frontendUrl;

        Success(String frontendUrl) {
            this.frontendUrl = frontendUrl;
        }

        @Override
        public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                            Authentication authentication) throws IOException {
            response.sendRedirect(frontendUrl + "/dashboard");
        }
    }

    static final class Failure implements AuthenticationFailureHandler {
        private final String frontendUrl;

        Failure(String frontendUrl) {
            this.frontendUrl = frontendUrl;
        }

        @Override
        public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                            AuthenticationException exception) throws IOException {
            String code = "oauth_failed";
            if (exception instanceof OAuth2AuthenticationException oae) {
                String errorCode = oae.getError().getErrorCode();
                // invalid_state_parameter / authorization_request_not_found => possible CSRF or expired login attempt
                if (errorCode != null && (errorCode.contains("state") || errorCode.contains("authorization_request_not_found"))) {
                    code = "invalid_state";
                } else if ("access_denied".equals(errorCode)) {
                    code = "access_denied";
                }
                log.info("GitHub OAuth login failed: {}", errorCode);
            }
            response.sendRedirect(frontendUrl + "/login?error=" + code);
        }
    }
}
