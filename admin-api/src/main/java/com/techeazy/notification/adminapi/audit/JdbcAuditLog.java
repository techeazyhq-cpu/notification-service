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
package com.techeazy.notification.adminapi.audit;

import com.techeazy.notification.adminapi.auth.AdminRole;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * Keeps audit events in {@code admin_audit_event}. The table rejects updates, deletes and truncation with a trigger,
 * so the log stays append-only even for a compromised application role (see ADR-019).
 */
class JdbcAuditLog implements AuditLog {

    private static final String MATCHING = """
            WHERE (CAST(:actor AS VARCHAR) IS NULL OR actor = :actor)
              AND (CAST(:outcome AS VARCHAR) IS NULL OR outcome = :outcome)
              AND (CAST(:from AS TIMESTAMPTZ) IS NULL OR occurred_at >= :from)
              AND (CAST(:to AS TIMESTAMPTZ) IS NULL OR occurred_at < :to)
            """;

    private final JdbcClient jdbc;

    JdbcAuditLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(AuditEvent event) {
        jdbc.sql("""
                INSERT INTO admin_audit_event (id, occurred_at, actor, actor_role, http_method, route, path,
                                               status_code, outcome, source_address, user_agent)
                VALUES (:id, :occurredAt, :actor, :actorRole, :httpMethod, :route, :path, :statusCode, :outcome,
                        :sourceAddress, :userAgent)
                """)
                .param("id", event.id())
                .param("occurredAt", Timestamp.from(event.occurredAt()))
                .param("actor", event.actor())
                .param("actorRole", event.actorRole() == null ? null : event.actorRole().name())
                .param("httpMethod", event.httpMethod())
                .param("route", event.route())
                .param("path", event.path())
                .param("statusCode", event.statusCode())
                .param("outcome", event.outcome().name())
                .param("sourceAddress", event.sourceAddress())
                .param("userAgent", event.userAgent())
                .update();
    }

    @Override
    public AuditPage search(AuditQuery query, int page, int size) {
        long total = withCriteria(jdbc.sql("SELECT count(*) FROM admin_audit_event " + MATCHING), query)
                .query(Long.class).single();
        List<AuditEvent> items = withCriteria(jdbc.sql("""
                SELECT id, occurred_at, actor, actor_role, http_method, route, path, status_code, outcome,
                       source_address, user_agent
                FROM admin_audit_event
                """ + MATCHING + """
                ORDER BY occurred_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """), query)
                .param("limit", size)
                .param("offset", (long) page * size)
                .query(JdbcAuditLog::toEvent)
                .list();
        return new AuditPage(items, page, size, total);
    }

    private static JdbcClient.StatementSpec withCriteria(JdbcClient.StatementSpec statement, AuditQuery query) {
        return statement
                .param("actor", query.actor())
                .param("outcome", query.outcome() == null ? null : query.outcome().name())
                .param("from", query.from() == null ? null : Timestamp.from(query.from()))
                .param("to", query.to() == null ? null : Timestamp.from(query.to()));
    }

    private static AuditEvent toEvent(ResultSet row, int rowNumber) throws SQLException {
        String actorRole = row.getString("actor_role");
        return new AuditEvent(row.getObject("id", UUID.class), row.getTimestamp("occurred_at").toInstant(),
                row.getString("actor"), actorRole == null ? null : AdminRole.valueOf(actorRole),
                row.getString("http_method"), row.getString("route"), row.getString("path"), row.getInt("status_code"),
                AuditOutcome.valueOf(row.getString("outcome")), row.getString("source_address"),
                row.getString("user_agent"));
    }
}
