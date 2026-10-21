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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Template drafting settings under {@code template-drafting.*} (ADR-041). Drafting is off until the provider can be
 * reached, with an API key where it needs one; every other value has a working default. Settings an administrator
 * saves in the console override the provider, base URL, API key, model, effort, token ceiling, budgets and rate limit
 * (ADR-044); the rest are operational and stay here.
 */
@ConfigurationProperties(prefix = "template-drafting")
public class DraftingProperties {

    private LlmProvider provider = LlmProvider.ANTHROPIC;
    private String baseUrl = "";
    private String apiKey = "";
    private String model = "claude-opus-5-5";
    private String effort = "low";
    private long maxTokens = 8000;
    private Duration timeout = Duration.ofSeconds(60);
    private boolean fallbacks = true;
    private long monthlyTokenBudget = 2_000_000;
    private long clientMonthlyTokenBudget = 200_000;
    private int requestsPerMinute = 5;
    private int maxAttempts = 2;
    private Duration settingsCacheTtl = Duration.ofSeconds(10);

    /** The LLM provider that writes drafts. */
    public LlmProvider getProvider() { return provider; }
    public void setProvider(LlmProvider provider) { this.provider = provider; }

    /** Where the provider is reached; blank for its own endpoint. Required for {@code OPENAI_COMPATIBLE}. */
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    /** The provider's API key; blank switches drafting off for a provider that needs one. */
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public boolean hasApiKey() { return apiKey != null && !apiKey.isBlank(); }

    /** The model that writes drafts, as the provider names it. */
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    /** How much an Anthropic model thinks first: low, medium, high, xhigh or max. Short templates need little. */
    public String getEffort() { return effort; }
    public void setEffort(String effort) { this.effort = effort; }

    /** The ceiling on one reply, thinking included; also bounds how far a burst of drafts can overshoot a budget. */
    public long getMaxTokens() { return maxTokens; }
    public void setMaxTokens(long maxTokens) { this.maxTokens = maxTokens; }

    /** How long one call to the model may take before it is abandoned. */
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration timeout) { this.timeout = timeout; }

    /** Let the API retry a declined brief on its recommended fallback model instead of returning the refusal. */
    public boolean isFallbacks() { return fallbacks; }
    public void setFallbacks(boolean fallbacks) { this.fallbacks = fallbacks; }

    /** Tokens, input and output together, that all drafting may spend per calendar month (UTC). */
    public long getMonthlyTokenBudget() { return monthlyTokenBudget; }
    public void setMonthlyTokenBudget(long monthlyTokenBudget) { this.monthlyTokenBudget = monthlyTokenBudget; }

    /** Tokens each client may spend per calendar month (UTC); 0 keeps drafting for administrators only. */
    public long getClientMonthlyTokenBudget() { return clientMonthlyTokenBudget; }
    public void setClientMonthlyTokenBudget(long clientMonthlyTokenBudget) { this.clientMonthlyTokenBudget = clientMonthlyTokenBudget; }

    /** Drafts each administrator, and each client, may ask for per minute. */
    public int getRequestsPerMinute() { return requestsPerMinute; }
    public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }

    /** Calls to the model per draft: the first, plus corrections when a reply breaks the channel's rules. */
    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    /** How long each instance keeps the settings it read from the database before reading them again. */
    public Duration getSettingsCacheTtl() { return settingsCacheTtl; }
    public void setSettingsCacheTtl(Duration settingsCacheTtl) { this.settingsCacheTtl = settingsCacheTtl; }
}
