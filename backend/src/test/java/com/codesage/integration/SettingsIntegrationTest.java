package com.codesage.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.codesage.settings.UserSettingsRepository;
import com.codesage.support.IntegrationTestBase;

class SettingsIntegrationTest extends IntegrationTestBase {

    @Autowired
    UserSettingsRepository settings;

    @Test
    void defaultsAreCreatedAndServerKeyIsReportedWithoutExposingIt() throws Exception {
        TestUser user = newUser();
        String body = mvc.perform(get("/api/settings").with(as(user)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProvider").value("anthropic"))
                .andExpect(jsonPath("$.aiKeyConfigured").value(true))
                .andExpect(jsonPath("$.aiKeySource").value("server"))
                .andExpect(jsonPath("$.enabledAnalyzers[0]").value("ai"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("test-ai-key");
    }

    @Test
    void personalApiKeyIsEncryptedAndOnlyAHintIsReturned() throws Exception {
        TestUser user = newUser();
        String key = "sk-ant-personal-0123456789abcdef";
        String body = mvc.perform(put("/api/settings/ai-key").with(as(user)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"apiKey\":\"" + key + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiKeySource").value("user"))
                .andExpect(jsonPath("$.aiKeyHint").value("••••cdef"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(key);
        var stored = settings.findByUserId(user.id()).orElseThrow();
        assertThat(stored.getAiApiKeyEncrypted()).startsWith("v1:").doesNotContain(key);

        mvc.perform(delete("/api/settings/ai-key").with(as(user)).with(csrf()))
                .andExpect(jsonPath("$.aiKeySource").value("server"));
    }

    @Test
    void updateValidatesInput() throws Exception {
        TestUser user = newUser();
        mvc.perform(put("/api/settings").with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiProvider\":\"openai\",\"aiModel\":\"gpt-5\",\"enabledAnalyzers\":[\"pmd\",\"rules\"],"
                                + "\"enabledLanguages\":[\"java\",\"python\"],\"minSeverity\":\"MEDIUM\",\"maxFiles\":20}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aiProvider").value("openai"))
                .andExpect(jsonPath("$.aiKeyConfigured").value(false))
                .andExpect(jsonPath("$.enabledLanguages.length()").value(2));
        mvc.perform(put("/api/settings").with(as(user)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"aiProvider\":\"evil\",\"aiModel\":\"x; rm -rf\",\"enabledAnalyzers\":[\"shell\"],"
                                + "\"enabledLanguages\":[],\"minSeverity\":\"NOPE\",\"maxFiles\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }
}
