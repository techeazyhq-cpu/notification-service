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

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Keeps the policies in {@code client_drafting_policy} (changeset {@code 029-client-drafting-policy}). Read on every
 * draft rather than cached, so a client an administrator switches off cannot start another draft.
 */
class JdbcClientDraftingPolicies implements ClientDraftingPolicies {

    private record SavedPolicy(UUID clientId, ClientDraftingPolicy policy) {}

    private final JdbcClient jdbc;
    private final Clock clock;

    JdbcClientDraftingPolicies(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public ClientDraftingPolicy of(UUID clientId) {
        return jdbc.sql("""
                SELECT client_id, drafting_enabled, monthly_token_budget FROM client_drafting_policy
                WHERE client_id = :clientId
                """)
                .param("clientId", clientId)
                .query(JdbcClientDraftingPolicies::read).optional()
                .map(SavedPolicy::policy)
                .orElse(ClientDraftingPolicy.DEFAULT);
    }

    @Override
    public Map<UUID, ClientDraftingPolicy> all() {
        return jdbc.sql("SELECT client_id, drafting_enabled, monthly_token_budget FROM client_drafting_policy")
                .query(JdbcClientDraftingPolicies::read).list().stream()
                .collect(Collectors.toUnmodifiableMap(SavedPolicy::clientId, SavedPolicy::policy));
    }

    @Override
    public void save(UUID clientId, ClientDraftingPolicy policy, String administrator) {
        jdbc.sql("""
                INSERT INTO client_drafting_policy (client_id, drafting_enabled, monthly_token_budget, updated_at,
                                                    updated_by)
                VALUES (:clientId, :enabled, :monthlyTokenBudget, :updatedAt, :updatedBy)
                ON CONFLICT (client_id) DO UPDATE SET drafting_enabled = EXCLUDED.drafting_enabled,
                    monthly_token_budget = EXCLUDED.monthly_token_budget, updated_at = EXCLUDED.updated_at,
                    updated_by = EXCLUDED.updated_by
                """)
                .param("clientId", clientId)
                .param("enabled", policy.enabled())
                .param("monthlyTokenBudget", policy.monthlyTokenBudget())
                .param("updatedAt", Timestamp.from(clock.instant()))
                .param("updatedBy", administrator)
                .update();
    }

    private static SavedPolicy read(ResultSet row, int rowNumber) throws SQLException {
        long budget = row.getLong("monthly_token_budget");
        Long monthlyTokenBudget = row.wasNull() ? null : budget;
        return new SavedPolicy(row.getObject("client_id", UUID.class),
                new ClientDraftingPolicy(row.getBoolean("drafting_enabled"), monthlyTokenBudget));
    }
}
