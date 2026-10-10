package com.codesage.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

import com.codesage.exception.ApiException;

/** Resolves the authenticated CodeSage user id. All ownership checks are keyed on this value. */
@Component
public class CurrentUser {

    public UUID requireId() {
        return currentId().orElseThrow(() ->
                new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required"));
    }

    public Optional<UUID> currentId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof OAuth2User principal)) {
            return Optional.empty();
        }
        Object raw = principal.getAttribute(CodeSageOAuth2UserService.USER_ID_ATTRIBUTE);
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.toString()));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
