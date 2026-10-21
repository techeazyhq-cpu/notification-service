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

import com.techeazy.notification.drafting.DraftingSettingsView.ApiKeySource;
import com.techeazy.notification.infra.AesGcmCipher;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The LLM drafting settings administrators edit in the console (ADR-044), in the single-row table
 * {@code template_drafting_settings}, with the API key encrypted under the secrets key (ADR-037). A saved row
 * overrides the {@code template-drafting.*} environment defaults; without a stored key the environment's key applies.
 *
 * <p>Both admin-api and client-api read it. Each instance keeps what it read for
 * {@link DraftingProperties#getSettingsCacheTtl()}, so a change reaches every instance within that time, and the
 * instance that saved it at once.
 */
public class DraftingSettingsStore implements DraftingSettingsSource {

    private static final String SELECT = """
            SELECT enabled, provider, base_url, api_key, model, effort, max_tokens, monthly_token_budget,
                client_monthly_token_budget, requests_per_minute, updated_at, updated_by
            FROM template_drafting_settings
            """;

    private static final String UPSERT = """
            INSERT INTO template_drafting_settings (id, enabled, provider, base_url, api_key, model, effort,
                max_tokens, monthly_token_budget, client_monthly_token_budget, requests_per_minute, updated_at,
                updated_by)
            VALUES (TRUE, :enabled, :provider, :baseUrl, :newKey, :model, :effort, :maxTokens, :monthlyTokenBudget,
                :clientMonthlyTokenBudget, :requestsPerMinute, :updatedAt, :updatedBy)
            ON CONFLICT (id) DO UPDATE SET enabled = EXCLUDED.enabled, provider = EXCLUDED.provider,
                base_url = EXCLUDED.base_url,
                api_key = CASE WHEN :replaceKey THEN EXCLUDED.api_key
                               WHEN :removeKey THEN NULL
                               ELSE template_drafting_settings.api_key END,
                model = EXCLUDED.model, effort = EXCLUDED.effort, max_tokens = EXCLUDED.max_tokens,
                monthly_token_budget = EXCLUDED.monthly_token_budget,
                client_monthly_token_budget = EXCLUDED.client_monthly_token_budget,
                requests_per_minute = EXCLUDED.requests_per_minute,
                updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
            """;

    private record StoredSettings(DraftingSettings settings, String storedApiKey, Instant updatedAt,
                                  String updatedBy) {}

    private record CachedSettings(DraftingSettings settings, Instant readAt) {}

    private final JdbcClient jdbc;
    private final AesGcmCipher cipher;
    private final DraftingProperties environment;
    private final Clock clock;
    private final AtomicReference<CachedSettings> cache = new AtomicReference<>();

    public DraftingSettingsStore(JdbcClient jdbc, AesGcmCipher cipher, DraftingProperties environment, Clock clock) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.environment = environment;
        this.clock = clock;
    }

    @Override
    public DraftingSettings current() {
        Instant now = clock.instant();
        CachedSettings cached = cache.get();
        if (cached != null && now.isBefore(cached.readAt().plus(environment.getSettingsCacheTtl()))) {
            return cached.settings();
        }
        DraftingSettings settings = stored().map(StoredSettings::settings).orElseGet(this::environmentDefaults);
        cache.set(new CachedSettings(settings, now));
        return settings;
    }

    /** The settings in effect for the console, read from the database rather than the cache. */
    public DraftingSettingsView describe() {
        Optional<StoredSettings> stored = stored();
        DraftingSettings settings = stored.map(StoredSettings::settings).orElseGet(this::environmentDefaults);
        return new DraftingSettingsView(settings.active(), settings.provider(), settings.baseUrl(),
                settings.endpoint(), apiKeySource(stored),
                DraftingSettings.hintOf(settings.apiKey()), settings.model(), settings.effort(), settings.maxTokens(),
                settings.monthlyTokenBudget(), settings.clientMonthlyTokenBudget(), settings.requestsPerMinute(),
                stored.map(StoredSettings::updatedAt).orElse(null), stored.map(StoredSettings::updatedBy).orElse(null));
    }

    /** Saves {@code change} as {@code administrator}'s and applies it to this instance at once. */
    public DraftingSettingsView save(DraftingSettingsChange change, String administrator) {
        jdbc.sql(UPSERT)
                .param("enabled", change.enabled())
                .param("provider", change.provider().name())
                .param("baseUrl", change.baseUrl() == null || change.baseUrl().isBlank() ? null
                        : change.baseUrl().strip())
                .param("newKey", change.replacesApiKey() ? cipher.encrypt(change.apiKey().strip()) : null)
                .param("replaceKey", change.replacesApiKey())
                .param("removeKey", change.removeApiKey())
                .param("model", change.model())
                .param("effort", change.effort())
                .param("maxTokens", change.maxTokens())
                .param("monthlyTokenBudget", change.monthlyTokenBudget())
                .param("clientMonthlyTokenBudget", change.clientMonthlyTokenBudget())
                .param("requestsPerMinute", change.requestsPerMinute())
                .param("updatedAt", Timestamp.from(clock.instant()))
                .param("updatedBy", administrator)
                .update();
        cache.set(null);
        return describe();
    }

    private ApiKeySource apiKeySource(Optional<StoredSettings> stored) {
        if (stored.map(StoredSettings::storedApiKey).isPresent()) {
            return ApiKeySource.STORED;
        }
        return environment.hasApiKey() ? ApiKeySource.ENVIRONMENT : ApiKeySource.NONE;
    }

    private DraftingSettings environmentDefaults() {
        return DraftingSettings.from(environment);
    }

    private Optional<StoredSettings> stored() {
        return jdbc.sql(SELECT).query(this::read).optional();
    }

    private StoredSettings read(ResultSet row, int rowNumber) throws SQLException {
        String storedApiKey = row.getString("api_key");
        String apiKey = storedApiKey == null ? environment.getApiKey() : cipher.decrypt(storedApiKey);
        DraftingSettings settings = new DraftingSettings(row.getBoolean("enabled"),
                LlmProvider.valueOf(row.getString("provider")), row.getString("base_url"), apiKey,
                row.getString("model"), row.getString("effort"), row.getLong("max_tokens"),
                row.getLong("monthly_token_budget"), row.getLong("client_monthly_token_budget"),
                row.getInt("requests_per_minute"));
        return new StoredSettings(settings, storedApiKey, row.getTimestamp("updated_at").toInstant(),
                row.getString("updated_by"));
    }
}
