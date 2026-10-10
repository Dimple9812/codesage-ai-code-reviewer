package com.codesage.security;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Service;

import com.codesage.auth.UserAccountService;

/**
 * Loads the GitHub profile after the OAuth code exchange (Spring Security has already validated the
 * OAuth state parameter), persists the user, and stores the access token encrypted server side.
 * The token itself is intentionally NOT placed in the session principal.
 */
@Service
public class CodeSageOAuth2UserService extends DefaultOAuth2UserService {

    public static final String USER_ID_ATTRIBUTE = "codesage_user_id";

    private final UserAccountService accounts;

    public CodeSageOAuth2UserService(UserAccountService accounts) {
        this.accounts = accounts;
    }

    @Override
    public OAuth2User loadUser(OAuth2UserRequest request) throws OAuth2AuthenticationException {
        OAuth2User gitHubUser = super.loadUser(request);
        return withAccount(gitHubUser, request);
    }

    OAuth2User withAccount(OAuth2User gitHubUser, OAuth2UserRequest request) {
        Object idAttr = gitHubUser.getAttribute("id");
        if (!(idAttr instanceof Number githubId)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("invalid_user_info"), "GitHub user id missing");
        }
        UUID userId = accounts.upsertOnLogin(
                githubId.longValue(),
                gitHubUser.getAttribute("login"),
                gitHubUser.getAttribute("name"),
                gitHubUser.getAttribute("email"),
                gitHubUser.getAttribute("avatar_url"),
                request.getAccessToken().getTokenValue(),
                String.join(",", request.getAccessToken().getScopes()));

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("id", githubId.longValue());
        attributes.put("login", gitHubUser.getAttribute("login"));
        attributes.put(USER_ID_ATTRIBUTE, userId.toString());
        return new DefaultOAuth2User(java.util.List.of(new SimpleGrantedAuthority("ROLE_USER")), attributes, "id");
    }
}
