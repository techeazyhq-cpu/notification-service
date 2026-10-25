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
 * How one client may use AI drafting, set by an administrator (ADR-045). A client without a saved policy has
 * {@link #DEFAULT}: drafting on, with the default client budget from the drafting settings.
 *
 * @param enabled            false keeps this client from drafting, whatever its budget
 * @param monthlyTokenBudget this client's own tokens per calendar month (UTC), replacing the default client budget;
 *                           null for the default, 0 to switch the client off
 */
public record ClientDraftingPolicy(boolean enabled, Long monthlyTokenBudget) {

    public static final ClientDraftingPolicy DEFAULT = new ClientDraftingPolicy(true, null);

    public ClientDraftingPolicy {
        if (monthlyTokenBudget != null && monthlyTokenBudget < 0) {
            throw new IllegalArgumentException("monthlyTokenBudget must not be negative");
        }
    }

    /** The budget in effect: this client's own, or else {@code defaultBudget}; 0 when the client is switched off. */
    public long monthlyTokenBudget(long defaultBudget) {
        if (!enabled) {
            return 0;
        }
        return monthlyTokenBudget == null ? defaultBudget : monthlyTokenBudget;
    }
}
