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
import com.techeazy.notification.application.ProviderDestinationRefusedException;
import com.techeazy.notification.domain.ProviderType;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Builds the model for the settings in effect, for any {@link LlmProvider} (ADR-044): Anthropic through its own SDK,
 * every other provider through the OpenAI-compatible adapter. It is asked for every draft, so a change saved in the
 * console is used by the next one.
 *
 * <p>Before a model is built, its endpoint must pass the {@link ProviderDestinationPolicy} like any provider's
 * (ADR-022): a public HTTPS host, or a host the operator trusts, such as a local Ollama. A refused endpoint gives a
 * model that fails every draft with the reason, so nothing is sent there and the author sees why.
 *
 * <p>An Anthropic SDK client holds a connection pool, so one is kept for the current key and endpoint and replaced
 * only when they change. The replaced client is not closed, because a draft may still be running on it; its idle
 * connections and threads end on their own.
 */
class LlmDraftModels implements DraftModels {

    private record KeyedClient(String apiKey, String endpoint, AnthropicClient client) {

        boolean servesFor(DraftingSettings settings) {
            return apiKey.equals(settings.apiKey()) && endpoint.equals(settings.endpoint());
        }
    }

    private final Function<DraftingSettings, AnthropicClient> anthropicClientFor;
    private final HttpClient http;
    private final Duration timeout;
    private final boolean fallbacks;
    private final ProviderDestinationPolicy destinations;
    private KeyedClient currentAnthropicClient;

    LlmDraftModels(Function<DraftingSettings, AnthropicClient> anthropicClientFor, HttpClient http, Duration timeout,
                   boolean fallbacks, ProviderDestinationPolicy destinations) {
        this.anthropicClientFor = anthropicClientFor;
        this.http = http;
        this.timeout = timeout;
        this.fallbacks = fallbacks;
        this.destinations = destinations;
    }

    @Override
    public Optional<DraftModel> forSettings(DraftingSettings settings) {
        if (!settings.reachable()) {
            return Optional.empty();
        }
        try {
            destinations.check(ProviderType.HTTP_JSON, Map.of("url", settings.endpoint()));
        } catch (ProviderDestinationRefusedException refused) {
            return Optional.of(refusing(settings, refused));
        }
        return Optional.of(switch (settings.provider().protocol()) {
            case ANTHROPIC_MESSAGES -> new ClaudeDraftModel(anthropicClient(settings), settings, fallbacks);
            case OPENAI_CHAT_COMPLETIONS -> new OpenAiCompatibleDraftModel(http, settings, timeout);
        });
    }

    private static DraftModel refusing(DraftingSettings settings, ProviderDestinationRefusedException refused) {
        String reason = "The drafting endpoint " + settings.endpoint() + " is not allowed: " + refused.getMessage()
                + "; add its host to PROVIDER_TRUSTED_HOSTS if it is yours";
        return prompt -> {
            throw new DraftModelUnavailableException(reason, refused);
        };
    }

    private synchronized AnthropicClient anthropicClient(DraftingSettings settings) {
        if (currentAnthropicClient == null || !currentAnthropicClient.servesFor(settings)) {
            currentAnthropicClient = new KeyedClient(settings.apiKey(), settings.endpoint(),
                    anthropicClientFor.apply(settings));
        }
        return currentAnthropicClient.client();
    }
}
