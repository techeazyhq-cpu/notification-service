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

/** The effective drafting settings: when they make drafting active, and that the API key never leaks into text. */
class DraftingSettingsTest {

    private static final String KEY = "sk-ant-api03-secret-value-wxyz";

    @Test
    void theEnvironmentDefaultsTurnDraftingOnOnlyWhenAKeyIsSet() {
        DraftingProperties properties = new DraftingProperties();

        assertThat(DraftingSettings.from(properties).active()).isFalse();

        properties.setApiKey(KEY);

        assertThat(DraftingSettings.from(properties).active()).isTrue();
        assertThat(DraftingSettings.from(properties).model()).isEqualTo("claude-opus-5-5");
    }

    @Test
    void switchedOffOrWithoutAKeyDraftingIsNotActive() {
        DraftingSettings settings = new DraftingSettings(true, KEY, "claude-opus-5-5", "low", 8000, 1, 1, 5);

        assertThat(settings.active()).isTrue();
        assertThat(settings.withEnabled(false).active()).isFalse();
        assertThat(settings.withApiKey(" ").active()).isFalse();
    }

    @Test
    void theApiKeyIsNeverPartOfTheTextForm() {
        DraftingSettings settings = new DraftingSettings(true, KEY, "claude-opus-5-5", "low", 8000, 1, 1, 5);

        assertThat(settings.toString()).doesNotContain("secret-value").contains("claude-opus-5-5");
    }

    @Test
    void theHintShowsOnlyTheLastFourCharacters() {
        assertThat(DraftingSettings.hintOf(KEY)).isEqualTo("…wxyz");
        assertThat(DraftingSettings.hintOf("abc")).isEqualTo("…");
        assertThat(DraftingSettings.hintOf(null)).isNull();
    }
}
