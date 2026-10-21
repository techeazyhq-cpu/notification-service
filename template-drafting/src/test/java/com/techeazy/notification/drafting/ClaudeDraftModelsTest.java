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
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** One SDK client per API key: reused while the key stays, replaced when an administrator changes it. */
class ClaudeDraftModelsTest {

    private final List<String> keysClientsWereBuiltFor = new ArrayList<>();
    private final List<AnthropicClient> builtClients = new ArrayList<>();
    private final ClaudeDraftModels models = new ClaudeDraftModels(apiKey -> {
        keysClientsWereBuiltFor.add(apiKey);
        AnthropicClient client = mock(AnthropicClient.class);
        builtClients.add(client);
        return client;
    }, true);

    private static DraftingSettings withKey(String apiKey) {
        return new DraftingSettings(true, apiKey, "claude-opus-5-5", "low", 8000, 1, 1, 5);
    }

    @Test
    void withoutAKeyThereIsNoModel() {
        assertThat(models.forSettings(withKey(""))).isEmpty();
        assertThat(keysClientsWereBuiltFor).isEmpty();
    }

    @Test
    void theClientIsReusedWhileTheKeyStaysTheSame() {
        assertThat(models.forSettings(withKey("sk-ant-one"))).isPresent();
        assertThat(models.forSettings(withKey("sk-ant-one"))).isPresent();

        assertThat(keysClientsWereBuiltFor).containsExactly("sk-ant-one");
    }

    @Test
    void aNewKeyBuildsANewClientWithoutClosingTheOneADraftMayStillUse() {
        models.forSettings(withKey("sk-ant-one"));

        models.forSettings(withKey("sk-ant-two"));

        assertThat(keysClientsWereBuiltFor).containsExactly("sk-ant-one", "sk-ant-two");
        verify(builtClients.getFirst(), never()).close();
    }
}
