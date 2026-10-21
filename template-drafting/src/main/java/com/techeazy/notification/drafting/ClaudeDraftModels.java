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

import java.util.Optional;
import java.util.function.Function;

/**
 * Builds a {@link ClaudeDraftModel} for the settings in effect. An SDK client holds a connection pool, so one is kept
 * for the current API key and replaced only when an administrator saves a different key. The replaced client is not
 * closed, because a draft may still be running on it; its idle connections and threads end on their own.
 */
class ClaudeDraftModels implements DraftModels {

    private record KeyedClient(String apiKey, AnthropicClient client) {}

    private final Function<String, AnthropicClient> clientForKey;
    private final boolean fallbacks;
    private KeyedClient current;

    ClaudeDraftModels(Function<String, AnthropicClient> clientForKey, boolean fallbacks) {
        this.clientForKey = clientForKey;
        this.fallbacks = fallbacks;
    }

    @Override
    public Optional<DraftModel> forSettings(DraftingSettings settings) {
        if (!settings.hasApiKey()) {
            return Optional.empty();
        }
        return Optional.of(new ClaudeDraftModel(clientFor(settings.apiKey()), settings, fallbacks));
    }

    private synchronized AnthropicClient clientFor(String apiKey) {
        if (current != null && current.apiKey().equals(apiKey)) {
            return current.client();
        }
        current = new KeyedClient(apiKey, clientForKey.apply(apiKey));
        return current.client();
    }
}
