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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/** Keeps the ledger in {@code template_draft_usage} (changeset {@code 025-template-draft-usage}). */
class JdbcDraftUsageLedger implements DraftUsageLedger {

    private static final String SINCE = "since";

    private final JdbcClient jdbc;

    JdbcDraftUsageLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(Usage usage) {
        jdbc.sql("""
                INSERT INTO template_draft_usage (id, occurred_at, actor, client_id, channel, model, input_tokens,
                                                  output_tokens, outcome)
                VALUES (:id, :occurredAt, :actor, :clientId, :channel, :model, :inputTokens, :outputTokens, :outcome)
                """)
                .param("id", usage.id())
                .param("occurredAt", Timestamp.from(usage.occurredAt()))
                .param("actor", usage.actor())
                .param("clientId", usage.clientId())
                .param("channel", usage.channel().name())
                .param("model", usage.model())
                .param("inputTokens", usage.inputTokens())
                .param("outputTokens", usage.outputTokens())
                .param("outcome", usage.outcome().name())
                .update();
    }

    @Override
    public long tokensSince(Instant since) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM template_draft_usage
                WHERE occurred_at >= :since
                """)
                .param(SINCE, Timestamp.from(since))
                .query(Long.class).single();
    }

    @Override
    public long tokensSince(UUID clientId, Instant since) {
        return jdbc.sql("""
                SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM template_draft_usage
                WHERE client_id = :clientId AND occurred_at >= :since
                """)
                .param("clientId", clientId)
                .param(SINCE, Timestamp.from(since))
                .query(Long.class).single();
    }
}
