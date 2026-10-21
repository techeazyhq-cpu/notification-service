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

import java.time.Instant;

/**
 * The drafting settings as the console shows them (ADR-044): everything but the key itself, which is write-only.
 *
 * @param enabled      whether drafting is on: switched on, and a key is available
 * @param apiKeySource where the key in effect comes from
 * @param apiKeyHint   the last characters of the key in effect, to recognise it; null without one
 * @param updatedAt    when an administrator last saved the settings; null while the environment defaults apply
 * @param updatedBy    who saved them; null while the environment defaults apply
 */
public record DraftingSettingsView(boolean enabled, ApiKeySource apiKeySource, String apiKeyHint, String model,
                                   String effort, long maxTokens, long monthlyTokenBudget,
                                   long clientMonthlyTokenBudget, int requestsPerMinute, Instant updatedAt,
                                   String updatedBy) {

    /** Where the API key in effect comes from. */
    public enum ApiKeySource {
        /** Saved by an administrator, encrypted in the database. */
        STORED,
        /** The {@code ANTHROPIC_API_KEY} the service was started with. */
        ENVIRONMENT,
        /** No key anywhere: drafting is off. */
        NONE
    }
}
