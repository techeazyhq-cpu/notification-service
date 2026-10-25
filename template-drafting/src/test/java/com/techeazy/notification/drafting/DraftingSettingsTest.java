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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The effective drafting settings: which LLM provider and endpoint they point at, when they make drafting active, and
 * that the API key never leaks into text.
 */
class DraftingSettingsTest {

    private static final String KEY = "sk-api03-secret-value-wxyz";

    private static DraftingSettings settings(LlmProvider provider, String baseUrl, String apiKey) {
        return new DraftingSettings(true, provider, baseUrl, apiKey, "some-model", "low", 8000, 1, 1, 5);
    }

    @Test
    void theEnvironmentDefaultsAreAnthropicAndOnOnlyWhenAKeyIsSet() {
        DraftingProperties properties = new DraftingProperties();

        assertThat(DraftingSettings.from(properties).provider()).isEqualTo(LlmProvider.ANTHROPIC);
        assertThat(DraftingSettings.from(properties).active()).isFalse();

        properties.setApiKey(KEY);

        assertThat(DraftingSettings.from(properties).active()).isTrue();
        assertThat(DraftingSettings.from(properties).model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void theEnvironmentCanNameAnyProviderAndEndpoint() {
        DraftingProperties properties = new DraftingProperties();
        properties.setProvider(LlmProvider.OLLAMA);
        properties.setBaseUrl("http://ollama:11434/v1");
        properties.setModel("llama3.1:8b");

        DraftingSettings settings = DraftingSettings.from(properties);

        assertThat(settings.active()).isTrue();
        assertThat(settings.endpoint()).isEqualTo("http://ollama:11434/v1");
        assertThat(settings.model()).isEqualTo("llama3.1:8b");
    }

    @Test
    void aProviderThatNeedsAKeyIsNotActiveWithoutOne() {
        for (LlmProvider provider : new LlmProvider[] {LlmProvider.ANTHROPIC, LlmProvider.OPENAI, LlmProvider.GEMINI,
                LlmProvider.DEEPSEEK}) {
            assertThat(settings(provider, null, null).active()).as(provider.name()).isFalse();
            assertThat(settings(provider, null, KEY).active()).as(provider.name()).isTrue();
        }
    }

    @Test
    void aLocalOllamaNeedsNoKey() {
        assertThat(settings(LlmProvider.OLLAMA, null, null).active()).isTrue();
    }

    @Test
    void anOpenAiCompatibleEndpointNeedsABaseUrlButNoKey() {
        assertThat(settings(LlmProvider.OPENAI_COMPATIBLE, null, null).active()).isFalse();
        assertThat(settings(LlmProvider.OPENAI_COMPATIBLE, "https://llm.example.com/v1", null).active()).isTrue();
    }

    @Test
    void withoutABaseUrlTheProvidersOwnEndpointIsUsed() {
        assertThat(settings(LlmProvider.OPENAI, null, KEY).endpoint()).isEqualTo("https://api.openai.com/v1");
        assertThat(settings(LlmProvider.GEMINI, " ", KEY).endpoint())
                .isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
        assertThat(settings(LlmProvider.DEEPSEEK, null, KEY).endpoint()).isEqualTo("https://api.deepseek.com");
        assertThat(settings(LlmProvider.OLLAMA, null, null).endpoint()).isEqualTo("http://localhost:11434/v1");
        assertThat(settings(LlmProvider.ANTHROPIC, null, KEY).endpoint()).isEqualTo("https://api.anthropic.com");
        assertThat(settings(LlmProvider.OPENAI, "https://proxy.example.com/v1/", KEY).endpoint())
                .isEqualTo("https://proxy.example.com/v1");
    }

    @Test
    void switchedOffDraftingIsNotActive() {
        assertThat(settings(LlmProvider.OLLAMA, null, null).withEnabled(false).active()).isFalse();
    }

    @Test
    void theApiKeyIsNeverPartOfTheTextForm() {
        assertThat(settings(LlmProvider.OPENAI, null, KEY).toString()).doesNotContain("secret-value")
                .contains("some-model").contains("OPENAI");
    }

    @Test
    void theHintShowsOnlyTheLastFourCharacters() {
        assertThat(DraftingSettings.hintOf(KEY)).isEqualTo("…wxyz");
        assertThat(DraftingSettings.hintOf("abc")).isEqualTo("…");
        assertThat(DraftingSettings.hintOf(null)).isNull();
    }
}
