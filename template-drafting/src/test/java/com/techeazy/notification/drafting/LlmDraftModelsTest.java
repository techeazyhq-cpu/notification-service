/*
 * Copyright 2026 Vasantha Kumar
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
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.drafting;

import com.anthropic.client.AnthropicClient;
import com.techeazy.notification.application.ProviderDestinationPolicy;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Picks the adapter for the configured provider, keeps one Anthropic SDK client per key and endpoint, and lets no
 * draft reach an endpoint the provider destination policy refuses (ADR-022).
 */
class LlmDraftModelsTest {

    private static final DraftModel.Prompt PROMPT = new DraftModel.Prompt("system rules", "the brief");

    private final List<String> anthropicClientsBuiltFor = new ArrayList<>();
    private final List<AnthropicClient> builtClients = new ArrayList<>();
    private final ProviderDestinationPolicy destinations = new ProviderDestinationPolicy(Set.of("ollama"), true,
            host -> List.of(InetAddress.getByName("203.0.113.10")));
    private final LlmDraftModels models = new LlmDraftModels(settings -> {
        anthropicClientsBuiltFor.add(settings.apiKey() + "@" + settings.endpoint());
        AnthropicClient client = mock(AnthropicClient.class);
        builtClients.add(client);
        return client;
    }, HttpClient.newHttpClient(), Duration.ofSeconds(5), true, destinations);

    private static DraftingSettings settings(LlmProvider provider, String baseUrl, String apiKey) {
        return new DraftingSettings(true, provider, baseUrl, apiKey, "some-model", "low", 8000, 1, 1, 5);
    }

    @Test
    void anthropicIsDraftedThroughItsOwnApi() {
        assertThat(models.forSettings(settings(LlmProvider.ANTHROPIC, null, "sk-ant-one")))
                .containsInstanceOf(ClaudeDraftModel.class);
    }

    @Test
    void everyOtherProviderIsDraftedThroughTheOpenAiCompatibleApi() {
        assertThat(models.forSettings(settings(LlmProvider.OPENAI, null, "sk-one")))
                .containsInstanceOf(OpenAiCompatibleDraftModel.class);
        assertThat(models.forSettings(settings(LlmProvider.GEMINI, null, "key")))
                .containsInstanceOf(OpenAiCompatibleDraftModel.class);
        assertThat(models.forSettings(settings(LlmProvider.DEEPSEEK, null, "key")))
                .containsInstanceOf(OpenAiCompatibleDraftModel.class);
        assertThat(models.forSettings(settings(LlmProvider.OLLAMA, "http://ollama:11434/v1", null)))
                .containsInstanceOf(OpenAiCompatibleDraftModel.class);
    }

    @Test
    void settingsThatCannotDraftGiveNoModel() {
        assertThat(models.forSettings(settings(LlmProvider.ANTHROPIC, null, ""))).isEmpty();
        assertThat(models.forSettings(settings(LlmProvider.OPENAI_COMPATIBLE, null, null))).isEmpty();
        assertThat(anthropicClientsBuiltFor).isEmpty();
    }

    @Test
    void theAnthropicClientIsReusedWhileTheKeyAndEndpointStayTheSame() {
        models.forSettings(settings(LlmProvider.ANTHROPIC, null, "sk-ant-one"));
        models.forSettings(settings(LlmProvider.ANTHROPIC, null, "sk-ant-one"));

        assertThat(anthropicClientsBuiltFor).containsExactly("sk-ant-one@https://api.anthropic.com");
    }

    @Test
    void aNewKeyBuildsANewClientWithoutClosingTheOneADraftMayStillUse() {
        models.forSettings(settings(LlmProvider.ANTHROPIC, null, "sk-ant-one"));

        models.forSettings(settings(LlmProvider.ANTHROPIC, null, "sk-ant-two"));

        assertThat(anthropicClientsBuiltFor).containsExactly("sk-ant-one@https://api.anthropic.com",
                "sk-ant-two@https://api.anthropic.com");
        verify(builtClients.getFirst(), never()).close();
    }

    @Test
    void anEndpointTheDestinationPolicyRefusesIsNeverCalled() {
        DraftModel internal = models.forSettings(settings(LlmProvider.OPENAI_COMPATIBLE,
                "http://169.254.169.254/v1", null)).orElseThrow();

        assertThatThrownBy(() -> internal.complete(PROMPT))
                .isInstanceOf(DraftModelUnavailableException.class).hasMessageContaining("169.254.169.254");
    }

    @Test
    void anAnthropicEndpointOverrideIsCheckedToo() {
        DraftModel internal = models.forSettings(settings(LlmProvider.ANTHROPIC, "http://10.0.0.5", "sk-ant-one"))
                .orElseThrow();

        assertThatThrownBy(() -> internal.complete(PROMPT)).isInstanceOf(DraftModelUnavailableException.class);
        assertThat(anthropicClientsBuiltFor).isEmpty();
    }
}
