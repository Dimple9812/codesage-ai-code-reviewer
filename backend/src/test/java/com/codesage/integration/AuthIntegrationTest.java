package com.codesage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import com.codesage.support.IntegrationTestBase;

class AuthIntegrationTest extends IntegrationTestBase {

    @Test
    void unauthenticatedApiCallsReturnJson401() throws Exception {
        mvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(header().exists("X-Correlation-Id"));
        mvc.perform(get("/api/repositories")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/dashboard/summary")).andExpect(status().isUnauthorized());
    }

    @Test
    void publicConfigReportsIntegrationStatusWithoutSecrets() throws Exception {
        MvcResult result = mvc.perform(get("/api/auth/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.githubConfigured").value(true))
                .andExpect(jsonPath("$.aiConfigured").value(true))
                .andReturn();
        assertThat(result.getResponse().getContentAsString())
                .doesNotContain("test-client-secret").doesNotContain("test-ai-key").doesNotContain("test-webhook-secret");
    }

    @Test
    void meReturnsProfileButNeverTheGitHubToken() throws Exception {
        TestUser user = newUser();
        MvcResult result = mvc.perform(get("/api/auth/me").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.login").value(user.login()))
                .andExpect(jsonPath("$.githubConnected").value(true))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain(user.token());
    }

    @Test
    void loginStartRedirectsToGitHubWithStateParameter() throws Exception {
        mvc.perform(get("/api/auth/github"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:3000/oauth2/authorization/github"));
        MvcResult result = mvc.perform(get("/oauth2/authorization/github"))
                .andExpect(status().is3xxRedirection())
                .andReturn();
        String location = result.getResponse().getHeader("Location");
        assertThat(location).startsWith("https://github.com/login/oauth/authorize")
                .contains("client_id=test-client-id")
                .contains("state=")
                .contains("redirect_uri=http://localhost:3000/login/oauth2/code/github");
    }

    @Test
    void callbackWithForgedStateIsRejected() throws Exception {
        // Start a real authorization request so a state is stored in the session...
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/oauth2/authorization/github").session(session)).andExpect(status().is3xxRedirection());
        // ...then return with a different (attacker-chosen) state.
        mvc.perform(get("/login/oauth2/code/github").session(session)
                        .param("code", "attacker-code").param("state", "forged-state"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:3000/login?error=invalid_state"));
        // No session at all (expired / CSRF login attempt)
        mvc.perform(get("/login/oauth2/code/github").param("code", "c").param("state", "s"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", "http://localhost:3000/login?error=invalid_state"));
    }

    @Test
    void stateChangingRequestsRequireCsrfToken() throws Exception {
        TestUser user = newUser();
        mvc.perform(post("/api/reviews").with(as(user)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"repositoryId\":\"" + java.util.UUID.randomUUID() + "\",\"type\":\"REPOSITORY\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    void logoutReturnsNoContent() throws Exception {
        TestUser user = newUser();
        mvc.perform(post("/api/auth/logout").with(as(user)).with(csrf())).andExpect(status().isNoContent());
    }

    @Test
    void disconnectRevokesStoredTokenSoGitHubCallsRequireReauthentication() throws Exception {
        TestUser user = newUser();
        mvc.perform(delete("/api/auth/account").with(as(user)).with(csrf())).andExpect(status().isNoContent());
        mvc.perform(get("/api/repositories").with(as(user)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GITHUB_REAUTH_REQUIRED"));
    }

    @Test
    void deletingAccountDataRemovesTheUser() throws Exception {
        TestUser user = newUser();
        mvc.perform(delete("/api/auth/account").param("deleteData", "true").with(as(user)).with(csrf()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/me").with(as(user))).andExpect(status().isUnauthorized());
    }

    @Test
    void clientRoutesForwardToTheSpaButUnknownMutationsAreDenied() throws Exception {
        mvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/index.html"));
        mvc.perform(get("/reviews/123/compare"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("/index.html"));
        mvc.perform(post("/dashboard").with(csrf())).andExpect(status().is4xxClientError());
    }

    @Test
    void healthEndpointIsPublicButOtherActuatorEndpointsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }
}
