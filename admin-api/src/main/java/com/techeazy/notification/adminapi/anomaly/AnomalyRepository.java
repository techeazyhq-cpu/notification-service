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

package com.techeazy.notification.adminapi.anomaly;

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Reads recorded anomalies for the admin console and records administrators' decisions on them (ADR-039). */
class AnomalyRepository {

    /** A combination of filters; {@code null} means any. */
    record Query(AnomalyStatus status, UUID clientId, String channel, Severity severity) {
    }

    /** One page of anomalies, newest hour first, with the total number that matched. */
    record Page(List<AnomalyView> items, int page, int size, long totalItems) {
    }

    /** How many anomalies are open for one channel and severity. */
    record OpenCount(String channel, Severity severity, long count) {
    }

    private static final String SELECT = """
            SELECT a.id, a.client_id, c.name AS client_name, a.channel, a.hour, a.feature, a.method, a.expected,
                   a.actual, a.score, a.severity, a.detail, a.status, a.detected_at, a.resolved_by, a.resolved_at,
                   a.resolution_note
            FROM traffic_anomaly a LEFT JOIN client c ON c.id = a.client_id""";

    private static final String FILTER = """
             WHERE (CAST(:status AS VARCHAR) IS NULL OR a.status = :status)
               AND (CAST(:client AS UUID) IS NULL OR a.client_id = :client)
               AND (CAST(:channel AS VARCHAR) IS NULL OR a.channel = :channel)
               AND (CAST(:severity AS VARCHAR) IS NULL OR a.severity = :severity)""";

    private final JdbcClient jdbc;

    AnomalyRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Anomalies matching the filters, newest hour first.
     *
     * @param query the filters
     * @param page  the page, from zero
     * @param size  anomalies per page
     * @return the page and the total that matched
     */
    Page search(Query query, int page, int size) {
        List<AnomalyView> items = filtered(SELECT + "\n" + FILTER
                + " ORDER BY a.hour DESC, a.detected_at DESC, a.id LIMIT :limit OFFSET :offset", query)
                .param("limit", size).param("offset", (long) page * size)
                .query((row, i) -> view(row)).list();
        long total = filtered("SELECT count(*) FROM traffic_anomaly a\n" + FILTER, query).query(Long.class).single();
        return new Page(items, page, size, total);
    }

    /**
     * One anomaly.
     *
     * @param id the anomaly
     * @return it, or empty if there is none with that id
     */
    Optional<AnomalyView> find(UUID id) {
        return jdbc.sql(SELECT + " WHERE a.id = :id").param("id", id).query((row, i) -> view(row)).optional();
    }

    /**
     * A tenant's anomalies in a period, oldest hour first.
     *
     * @param clientId the tenant
     * @param channel  the channel's name, or {@code null} for every channel
     * @param from     the first hour, inclusive
     * @param to       the end, exclusive
     * @return the anomalies of those hours
     */
    List<AnomalyView> forClient(UUID clientId, String channel, Instant from, Instant to) {
        return jdbc.sql(SELECT + " WHERE a.client_id = :client AND (CAST(:channel AS VARCHAR) IS NULL OR a.channel = :channel) "
                        + "AND a.hour >= :from AND a.hour < :to ORDER BY a.hour, a.feature")
                .param("client", clientId).param("channel", channel)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((row, i) -> view(row)).list();
    }

    /**
     * Records an administrator's decision. A decision may be changed, but an anomaly is never reopened.
     *
     * @param id     the anomaly
     * @param status {@link AnomalyStatus#CONFIRMED} or {@link AnomalyStatus#FALSE_ALARM}
     * @param actor  the administrator
     * @param note   why, or {@code null}
     * @param at     when
     * @return the anomaly as it now stands, or empty if there is none with that id
     */
    Optional<AnomalyView> resolve(UUID id, AnomalyStatus status, String actor, String note, Instant at) {
        if (status == AnomalyStatus.OPEN) {
            throw new IllegalArgumentException("An anomaly is resolved as confirmed or a false alarm, never reopened");
        }
        int updated = jdbc.sql("UPDATE traffic_anomaly SET status = :status, resolved_by = :actor, resolved_at = :at, "
                        + "resolution_note = :note WHERE id = :id")
                .param("status", status.name()).param("actor", actor).param("at", Timestamp.from(at))
                .param("note", note).param("id", id).update();
        return updated == 0 ? Optional.empty() : find(id);
    }

    /** How many anomalies are open, per channel and severity; combinations with none are absent. */
    List<OpenCount> openCounts() {
        return jdbc.sql("SELECT channel, severity, count(*) AS open FROM traffic_anomaly WHERE status = 'OPEN' "
                        + "GROUP BY channel, severity")
                .query((row, i) -> new OpenCount(row.getString("channel"), Severity.valueOf(row.getString("severity")),
                        row.getLong("open"))).list();
    }

    /**
     * The tenant's name.
     *
     * @param clientId the tenant
     * @return its name, or empty if there is no such tenant
     */
    Optional<String> clientName(UUID clientId) {
        return jdbc.sql("SELECT name FROM client WHERE id = :client").param("client", clientId).query(String.class).optional();
    }

    private JdbcClient.StatementSpec filtered(String sql, Query query) {
        Map<String, Object> params = new HashMap<>();
        params.put("status", query.status() == null ? null : query.status().name());
        params.put("client", query.clientId());
        params.put("channel", query.channel());
        params.put("severity", query.severity() == null ? null : query.severity().name());
        return jdbc.sql(sql).params(params);
    }

    private static AnomalyView view(ResultSet row) throws SQLException {
        Timestamp resolvedAt = row.getTimestamp("resolved_at");
        return new AnomalyView(row.getObject("id", UUID.class), row.getObject("client_id", UUID.class),
                row.getString("client_name"), row.getString("channel"), row.getTimestamp("hour").toInstant(),
                Feature.valueOf(row.getString("feature")), DetectionMethod.valueOf(row.getString("method")),
                row.getDouble("expected"), row.getDouble("actual"), row.getDouble("score"),
                Severity.valueOf(row.getString("severity")), row.getString("detail"),
                AnomalyStatus.valueOf(row.getString("status")), row.getTimestamp("detected_at").toInstant(),
                row.getString("resolved_by"), resolvedAt == null ? null : resolvedAt.toInstant(),
                row.getString("resolution_note"));
    }
}
