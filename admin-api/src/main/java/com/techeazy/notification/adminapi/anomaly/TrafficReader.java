/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi.anomaly;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Reads measured hours back from {@code traffic_hourly} for scoring. Every per-tenant query is a range or point lookup
 * on the table's primary key {@code (client_id, channel, hour)}.
 */
class TrafficReader {

    private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() { };
    private static final Duration WEEK = Duration.ofDays(7);
    private static final String COLUMNS = "client_id, channel, hour, messages, recipients, new_recipients, failed, "
            + "largest_request, destinations::text AS destinations, error_codes::text AS error_codes";

    private final JdbcClient jdbc;
    private final ObjectMapper json = new ObjectMapper();

    TrafficReader(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every tenant and channel measured for one hour.
     *
     * @param hour the start of the hour
     * @return one entry per tenant and channel with traffic in the hour
     */
    List<TrafficHour> hour(Instant hour) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM traffic_hourly WHERE hour = :hour")
                .param("hour", Timestamp.from(hour))
                .query((row, i) -> traffic(row)).list();
    }

    /**
     * What scoring one tenant's hour needs to know about its past.
     *
     * @param clientId the tenant
     * @param channel  the channel's name
     * @param hour     the hour being scored
     * @param weeks    how many earlier weeks make the baseline
     * @return the tenant's first measured hour, the same hour of the week in each baseline week that had traffic, and
     *         every destination used in the baseline period
     */
    TenantHistory history(UUID clientId, String channel, Instant hour, int weeks) {
        Instant firstSeen = firstSeen(clientId, channel).orElse(hour);
        List<Timestamp> slots = IntStream.rangeClosed(1, weeks)
                .mapToObj(week -> Timestamp.from(hour.minus(WEEK.multipliedBy(week)))).toList();
        List<TrafficHour> sameHourOfWeek = slots.isEmpty() ? List.of() : jdbc.sql("SELECT " + COLUMNS
                        + " FROM traffic_hourly WHERE client_id = :client AND channel = :channel AND hour IN (:slots)")
                .param("client", clientId).param("channel", channel).param("slots", slots)
                .query((row, i) -> traffic(row)).list();
        List<String> destinations = jdbc.sql("SELECT DISTINCT jsonb_object_keys(destinations) FROM traffic_hourly "
                        + "WHERE client_id = :client AND channel = :channel AND hour >= :from AND hour < :hour")
                .param("client", clientId).param("channel", channel)
                .param("from", Timestamp.from(hour.minus(WEEK.multipliedBy(weeks))))
                .param("hour", Timestamp.from(hour))
                .query(String.class).list();
        return new TenantHistory(firstSeen, sameHourOfWeek, new HashSet<>(destinations));
    }

    /**
     * The first hour ever measured for a tenant on a channel.
     *
     * @param clientId the tenant
     * @param channel  the channel's name
     * @return the hour, or empty if the tenant never sent on the channel
     */
    Optional<Instant> firstSeen(UUID clientId, String channel) {
        return jdbc.sql("SELECT min(hour) FROM traffic_hourly WHERE client_id = :client AND channel = :channel")
                .param("client", clientId).param("channel", channel)
                .query(Timestamp.class).optional()
                .map(Timestamp::toInstant);
    }

    /**
     * A tenant's measured hours in a period, oldest first.
     *
     * @param clientId the tenant
     * @param channel  the channel's name, or {@code null} for every channel
     * @param from     the first hour, inclusive
     * @param to       the end, exclusive
     * @return the hours that had traffic; hours without traffic are absent
     */
    List<TrafficHour> range(UUID clientId, String channel, Instant from, Instant to) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM traffic_hourly WHERE client_id = :client "
                        + "AND (CAST(:channel AS VARCHAR) IS NULL OR channel = :channel) AND hour >= :from AND hour < :to "
                        + "ORDER BY hour, channel")
                .param("client", clientId).param("channel", channel)
                .param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .query((row, i) -> traffic(row)).list();
    }

    private TrafficHour traffic(ResultSet row) throws SQLException {
        return new TrafficHour(row.getObject("client_id", UUID.class), row.getString("channel"),
                row.getTimestamp("hour").toInstant(), row.getInt("messages"), row.getInt("recipients"),
                row.getInt("new_recipients"), row.getInt("failed"), row.getInt("largest_request"),
                counts(row.getString("destinations")), counts(row.getString("error_codes")));
    }

    private Map<String, Integer> counts(String value) {
        try {
            return Map.copyOf(json.readValue(value, COUNTS));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored counts are not valid JSON", e);
        }
    }
}
