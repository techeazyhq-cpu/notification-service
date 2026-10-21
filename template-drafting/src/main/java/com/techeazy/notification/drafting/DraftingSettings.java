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
 * @param apiKey                   the Anthropic API key; never part of {@link #toString()}
 * @param model                    the Claude model that writes drafts
 * @param effort                   how much the model thinks: low, medium, high, xhigh or max
 * @param maxTokens                the ceiling on one reply, thinking included
 * @param monthlyTokenBudget       tokens all drafting may spend per calendar month (UTC)
 * @param clientMonthlyTokenBudget tokens each client may spend per calendar month (UTC); 0 keeps drafting for
 *                                 administrators only
 * @param requestsPerMinute        drafts each administrator, and each client, may ask for per minute
 */
public record DraftingSettings(boolean enabled, String apiKey, String model, String effort, long maxTokens,
                               long monthlyTokenBudget, long clientMonthlyTokenBudget, int requestsPerMinute) {

    private static final int HINT_LENGTH = 4;

    /** The environment defaults: on exactly when an API key is configured there. */
    public static DraftingSettings from(DraftingProperties properties) {
        return new DraftingSettings(properties.hasApiKey(), properties.getApiKey(), properties.getModel(),
                properties.getEffort(), properties.getMaxTokens(), properties.getMonthlyTokenBudget(),
                properties.getClientMonthlyTokenBudget(), properties.getRequestsPerMinute());
    }

    /** The last characters of a key, enough to recognise it and never enough to use it; null when there is none. */
    public static String hintOf(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return apiKey.length() <= HINT_LENGTH ? "…" : "…" + apiKey.substring(apiKey.length() - HINT_LENGTH);
    }

    /** Drafting can run: switched on and with a key to call the model. */
    public boolean active() {
        return enabled && hasApiKey();
    }

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    public DraftingSettings withEnabled(boolean switchedOn) {
        return new DraftingSettings(switchedOn, apiKey, model, effort, maxTokens, monthlyTokenBudget,
                clientMonthlyTokenBudget, requestsPerMinute);
    }

    public DraftingSettings withApiKey(String replacement) {
        return new DraftingSettings(enabled, replacement, model, effort, maxTokens, monthlyTokenBudget,
                clientMonthlyTokenBudget, requestsPerMinute);
    }

    @Override
    public String toString() {
        return "DraftingSettings[enabled=" + enabled + ", apiKey=" + hintOf(apiKey) + ", model=" + model
                + ", effort=" + effort + ", maxTokens=" + maxTokens + ", monthlyTokenBudget=" + monthlyTokenBudget
                + ", clientMonthlyTokenBudget=" + clientMonthlyTokenBudget + ", requestsPerMinute="
                + requestsPerMinute + "]";
    }
}
