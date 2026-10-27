/*
 * Copyright 2026 Vasantha Kumar Kesavan
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.ProviderDestinationPolicy;
import com.techeazy.notification.drafting.DraftingSettingsChange;
import com.techeazy.notification.drafting.DraftingSettingsStore;
import com.techeazy.notification.drafting.DraftingSettingsView;
import com.techeazy.notification.drafting.DraftingSettingsView.ApiKeySource;
import com.techeazy.notification.drafting.LlmProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * Administrators read and change the AI drafting settings (ADR-044): any LLM provider, at an endpoint the destination
 * policy allows (ADR-022). The API key goes in and never comes back.
 */
class TemplateDraftingSettingsControllerTest {

    private static final String PATH = "/api/admin/settings/template-drafting";
    private static final String CONSOLE_KEY = "sk-ant-api03-console-secret-9876";
    private static final DraftingSettingsView SAVED = new DraftingSettingsView(true, LlmProvider.ANTHROPIC, null,
            "https://api.anthropic.com", ApiKeySource.STORED, "…9876", "claude-sonnet-5-5", "medium", 4000,
            1_500_000, 50_000, 3, Instant.parse("2026-10-21T12:00:00Z"), "alice");
    private static final ProviderDestinationPolicy DESTINATIONS = new ProviderDestinationPolicy(
            Set.of("host.docker.internal"), true, host -> List.of(InetAddress.getByName("203.0.113.10")));

    private final DraftingSettingsStore store = mock(DraftingSettingsStore.class);
    private final UsernamePasswordAuthenticationToken alice =
            new UsernamePasswordAuthenticationToken("alice", null, List.of());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new TemplateDraftingSettingsController(store, DESTINATIONS))
                .setControllerAdvice(new AdminErrorHandler(Optional::empty)).build();
        when(store.describe()).thenReturn(SAVED);
        when(store.save(any(), anyString())).thenReturn(SAVED);
    }

    private MockHttpServletResponse save(String body) throws Exception {
        return mvc.perform(put(PATH).principal(alice).contentType(MediaType.APPLICATION_JSON).content(body))
                .andReturn().getResponse();
    }

    private static String settings(String apiKeyAndRemoval, String effort, String model, int requestsPerMinute) {
        return settings("ANTHROPIC", null, apiKeyAndRemoval, effort, model, requestsPerMinute);
    }

    private static String settings(String provider, String baseUrl, String apiKeyAndRemoval, String effort,
                                   String model, int requestsPerMinute) {
        String baseUrlField = baseUrl == null ? "" : "\"baseUrl\":\"" + baseUrl + "\",";
        return """
                {"enabled":true,"provider":"%s",%s%s"model":"%s","effort":"%s","maxTokens":4000,
                 "monthlyTokenBudget":1500000,"clientMonthlyTokenBudget":50000,"requestsPerMinute":%d}
                """.formatted(provider, baseUrlField, apiKeyAndRemoval, model, effort, requestsPerMinute);
    }

    @Test
    void theSettingsAreShownWithAHintOfTheKeyOnly() throws Exception {
        MockHttpServletResponse response = mvc.perform(get(PATH).principal(alice)).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"apiKeySource\":\"STORED\"")
                .contains("\"apiKeyHint\":\"…9876\"").contains("\"model\":\"claude-sonnet-5-5\"");
    }

    @Test
    void aChangeIsSavedAsTheSignedInAdministratorAndTheKeyIsNotEchoed() throws Exception {
        MockHttpServletResponse response = save(settings("\"apiKey\":\"" + CONSOLE_KEY + "\",",
                "medium", "claude-sonnet-5-5", 3));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).doesNotContain("console-secret");
        verify(store).save(new DraftingSettingsChange(true, LlmProvider.ANTHROPIC, null, CONSOLE_KEY, false,
                "claude-sonnet-5-5", "medium", 4000, 1_500_000, 50_000, 3), "alice");
    }

    @Test
    void withoutAKeyTheStoredOneIsKept() throws Exception {
        save(settings("", "low", "claude-opus-5-5", 5));

        verify(store).save(new DraftingSettingsChange(true, LlmProvider.ANTHROPIC, null, null, false,
                "claude-opus-5-5", "low", 4000, 1_500_000, 50_000, 5), "alice");
    }

    @Test
    void aLocalOllamaOnATrustedHostIsSavedWithoutAKey() throws Exception {
        MockHttpServletResponse response = save(settings("OLLAMA", "http://host.docker.internal:11434/v1", "",
                "low", "llama3.1:8b", 10));

        assertThat(response.getStatus()).isEqualTo(200);
        verify(store).save(new DraftingSettingsChange(true, LlmProvider.OLLAMA, "http://host.docker.internal:11434/v1",
                null, false, "llama3.1:8b", "low", 4000, 1_500_000, 50_000, 10), "alice");
    }

    @Test
    void aPublicHttpsProviderIsSaved() throws Exception {
        assertThat(save(settings("OPENAI", null, "\"apiKey\":\"sk-test\",", "low", "gpt-test", 5)).getStatus())
                .isEqualTo(200);
        assertThat(save(settings("GEMINI", null, "\"apiKey\":\"key\",", "low", "gemini-test", 5)).getStatus())
                .isEqualTo(200);
    }

    @Test
    void anOpenAiCompatibleProviderNeedsABaseUrl() throws Exception {
        MockHttpServletResponse response = save(settings("OPENAI_COMPATIBLE", null, "", "low", "my-model", 5));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("base URL");
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void anEndpointTheDestinationPolicyRefusesIsNotSavedAndTheReasonIsGiven() throws Exception {
        MockHttpServletResponse response = save(settings("OPENAI_COMPATIBLE", "http://169.254.169.254/v1", "",
                "low", "my-model", 5));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("PROVIDER_DESTINATION_REFUSED")
                .contains("169.254.169.254");
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void anUnknownProviderIsRefused() throws Exception {
        assertThat(save(settings("SKYNET", null, "", "low", "m", 5)).getStatus()).isEqualTo(400);
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void anUnknownEffortIsRefused() throws Exception {
        assertThat(save(settings("", "extreme", "claude-opus-5-5", 5)).getStatus()).isEqualTo(400);
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void aModelNameThatIsNotAnIdentifierIsRefused() throws Exception {
        assertThat(save(settings("", "low", "claude opus; drop", 5)).getStatus()).isEqualTo(400);
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void atLeastOneDraftAMinuteMustBeAllowed() throws Exception {
        assertThat(save(settings("", "low", "claude-opus-5-5", 0)).getStatus()).isEqualTo(400);
        verify(store, never()).save(any(), anyString());
    }

    @Test
    void replacingAndRemovingTheKeyAtOnceIsRefused() throws Exception {
        MockHttpServletResponse response = save(settings("\"apiKey\":\"" + CONSOLE_KEY + "\",\"removeApiKey\":true,",
                "low", "claude-opus-5-5", 5));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).doesNotContain("console-secret");
        verify(store, never()).save(any(), anyString());
    }
}
