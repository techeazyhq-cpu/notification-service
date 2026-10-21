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

/**
 * The drafting settings in effect (ADR-044): the ones an administrator saved in the console, or else the
 * {@code template-drafting.*} defaults from the environment. Read afresh for every draft, so a change applies to the
 * next one without a restart.
 *
 * @param enabled                  false when an administrator switched drafting off
 * @param provider                 the LLM provider that writes drafts
 * @param baseUrl                  where the provider is reached; blank for the provider's own endpoint
 * @param apiKey                   the provider's API key, if it needs one; never part of {@link #toString()}
 * @param model                    the model, as the provider names it
 * @param effort                   how much the model thinks: low, medium, high, xhigh or max; Anthropic only
 * @param maxTokens                the ceiling on one reply, thinking included
 * @param monthlyTokenBudget       tokens all drafting may spend per calendar month (UTC)
 * @param clientMonthlyTokenBudget tokens each client may spend per calendar month (UTC); 0 keeps drafting for
 *                                 administrators only
 * @param requestsPerMinute        drafts each administrator, and each client, may ask for per minute
 */
public record DraftingSettings(boolean enabled, LlmProvider provider, String baseUrl, String apiKey, String model,
                               String effort, long maxTokens, long monthlyTokenBudget, long clientMonthlyTokenBudget,
                               int requestsPerMinute) {

    private static final int HINT_LENGTH = 4;

    /** The environment defaults: on once the provider can be reached, with a key where it needs one. */
    public static DraftingSettings from(DraftingProperties properties) {
        DraftingSettings settings = new DraftingSettings(true, properties.getProvider(), properties.getBaseUrl(),
                properties.getApiKey(), properties.getModel(), properties.getEffort(), properties.getMaxTokens(),
                properties.getMonthlyTokenBudget(), properties.getClientMonthlyTokenBudget(),
                properties.getRequestsPerMinute());
        return settings.withEnabled(settings.reachable());
    }

    /** The last characters of a key, enough to recognise it and never enough to use it; null when there is none. */
    public static String hintOf(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey.length() <= HINT_LENGTH ? "…" : "…" + apiKey.substring(apiKey.length() - HINT_LENGTH);
    }

    /** Drafting can run: switched on, with an endpoint and, where the provider needs one, a key. */
    public boolean active() {
        return enabled && reachable();
    }

    /** The provider has an endpoint and, where it needs one, a key. */
    public boolean reachable() {
        return endpoint() != null && (hasApiKey() || !provider.apiKeyRequired());
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** The base URL without a trailing slash, or the provider's own endpoint; null when neither exists. */
    public String endpoint() {
        if (baseUrl == null || baseUrl.isBlank()) {
            return provider.defaultEndpoint();
        }
        String trimmed = baseUrl.strip();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    public DraftingSettings withEnabled(boolean switchedOn) {
        return new DraftingSettings(switchedOn, provider, baseUrl, apiKey, model, effort, maxTokens,
                monthlyTokenBudget, clientMonthlyTokenBudget, requestsPerMinute);
    }

    @Override
    public String toString() {
        return "DraftingSettings[enabled=" + enabled + ", provider=" + provider + ", baseUrl=" + baseUrl
                + ", apiKey=" + hintOf(apiKey) + ", model=" + model + ", effort=" + effort + ", maxTokens="
                + maxTokens + ", monthlyTokenBudget=" + monthlyTokenBudget + ", clientMonthlyTokenBudget="
                + clientMonthlyTokenBudget + ", requestsPerMinute=" + requestsPerMinute + "]";
    }
}
