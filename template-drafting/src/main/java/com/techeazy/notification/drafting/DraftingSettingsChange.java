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
 * What an administrator saves on the AI drafting settings page (ADR-044).
 *
 * @param apiKey       a new Anthropic API key, or null to keep the one in effect; never part of {@link #toString()}
 * @param removeApiKey true to delete the stored key, so the environment's key, if any, applies again
 */
public record DraftingSettingsChange(boolean enabled, String apiKey, boolean removeApiKey, String model,
                                     String effort, long maxTokens, long monthlyTokenBudget,
                                     long clientMonthlyTokenBudget, int requestsPerMinute) {

    public boolean replacesApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public String toString() {
        return "DraftingSettingsChange[enabled=" + enabled + ", apiKey=" + DraftingSettings.hintOf(apiKey)
                + ", removeApiKey=" + removeApiKey + ", model=" + model + ", effort=" + effort + ", maxTokens="
                + maxTokens + ", monthlyTokenBudget=" + monthlyTokenBudget + ", clientMonthlyTokenBudget="
                + clientMonthlyTokenBudget + ", requestsPerMinute=" + requestsPerMinute + "]";
    }
}
