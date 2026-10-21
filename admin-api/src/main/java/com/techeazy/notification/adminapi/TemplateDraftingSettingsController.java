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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.ProviderDestinationPolicy;
import com.techeazy.notification.domain.ProviderType;
import com.techeazy.notification.drafting.DraftingSettings;
import com.techeazy.notification.drafting.DraftingSettingsChange;
import com.techeazy.notification.drafting.DraftingSettingsStore;
import com.techeazy.notification.drafting.DraftingSettingsView;
import com.techeazy.notification.drafting.LlmProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * The AI drafting settings administrators edit in the console (ADR-044): whether drafting is on, the LLM provider and
 * its base URL, the API key, the model, its effort and token ceiling, the monthly budgets and the per-minute limit.
 * The endpoint must pass the provider destination policy (ADR-022), like any provider's. The key is write-only: it is
 * accepted here and never returned, only a hint of its last characters. Reading is for any administrator, changing
 * for {@code ADMIN}, and every change is in the audit log (ADR-019) without its body.
 */
@RestController
@RequestMapping("/api/admin/settings/template-drafting")
class TemplateDraftingSettingsController {

    /**
     * The settings to save.
     *
     * @param provider     the LLM provider that writes drafts
     * @param baseUrl      where it is reached; absent for the provider's own endpoint
     * @param apiKey       a new key, or absent to keep the one in effect
     * @param removeApiKey delete the stored key, so the environment's key, if any, applies again
     */
    record SettingsInput(boolean enabled,
                         @NotNull LlmProvider provider,
                         @Size(max = 500) String baseUrl,
                         @Size(max = 512) String apiKey,
                         boolean removeApiKey,
                         @NotNull @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,63}") String model,
                         @NotNull @Pattern(regexp = "low|medium|high|xhigh|max") String effort,
                         @Min(256) @Max(128_000) long maxTokens,
                         @Min(0) @Max(10_000_000_000L) long monthlyTokenBudget,
                         @Min(0) @Max(10_000_000_000L) long clientMonthlyTokenBudget,
                         @Min(1) @Max(600) int requestsPerMinute) {

        DraftingSettingsChange toChange() {
            return new DraftingSettingsChange(enabled, provider, baseUrl, apiKey, removeApiKey, model, effort,
                    maxTokens, monthlyTokenBudget, clientMonthlyTokenBudget, requestsPerMinute);
        }

        @Override
        public String toString() {
            return toChange().toString();
        }
    }

    private final DraftingSettingsStore settings;
    private final ProviderDestinationPolicy destinations;

    TemplateDraftingSettingsController(DraftingSettingsStore settings, ProviderDestinationPolicy destinations) {
        this.settings = settings;
        this.destinations = destinations;
    }

    @GetMapping
    DraftingSettingsView show() {
        return settings.describe();
    }

    @PutMapping
    DraftingSettingsView save(Authentication authentication, @Valid @RequestBody SettingsInput in) {
        DraftingSettingsChange change = in.toChange();
        if (change.replacesApiKey() && change.removeApiKey()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Either give a new API key or remove the stored one, not both");
        }
        String endpoint = endpointOf(change);
        if (endpoint == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    change.provider() + " needs a base URL, such as https://llm.example.com/v1");
        }
        destinations.check(ProviderType.HTTP_JSON, Map.of("url", endpoint));
        return settings.save(change, authentication.getName());
    }

    private static String endpointOf(DraftingSettingsChange change) {
        return new DraftingSettings(change.enabled(), change.provider(), change.baseUrl(), null, change.model(),
                change.effort(), change.maxTokens(), change.monthlyTokenBudget(), change.clientMonthlyTokenBudget(),
                change.requestsPerMinute()).endpoint();
    }
}
