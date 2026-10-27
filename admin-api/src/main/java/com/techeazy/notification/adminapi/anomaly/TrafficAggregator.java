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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Measures each tenant's traffic for one hour from {@code notification_message} and {@code notification_request}, and
 * stores it in {@code traffic_hourly} (ADR-039). It reads through one connection and writes through another, so the
 * reads can go to a read-only role or a read replica (ADR-050). Every count is made in the database and only grouped totals come back,
 * at most one row per tenant, channel and destination. Phone numbers are grouped by their first three digits and mapped
 * to a calling code here, so no address leaves the database.
 *
 * <p>Storing replaces an earlier measurement of the same hour, so a repeated or concurrent run on several instances
 * writes the same row.
 */
class TrafficAggregator {

    static final String OTHER_DOMAIN = "other";

    private static final String COUNTS = """
            SELECT client_id, channel, count(*) AS messages, count(DISTINCT recipient_fingerprint) AS recipients,
                   count(*) FILTER (WHERE status = 'FAILED') AS failed
            FROM notification_message
            WHERE created_at >= :start AND created_at < :end
            GROUP BY client_id, channel""";

    private static final String NEW_RECIPIENTS = """
            SELECT m.client_id, m.channel, count(DISTINCT m.recipient_fingerprint) AS new_recipients
            FROM notification_message m
            WHERE m.created_at >= :start AND m.created_at < :end AND m.recipient_fingerprint IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM notification_message earlier
                              WHERE earlier.client_id = m.client_id
                                AND earlier.recipient_fingerprint = m.recipient_fingerprint
                                AND earlier.created_at >= :since AND earlier.created_at < :start)
            GROUP BY m.client_id, m.channel""";

    private static final String DESTINATIONS = """
            SELECT client_id, channel,
                   CASE WHEN channel = 'EMAIL' THEN lower(split_part(recipient, '@', 2))
                        ELSE substring(recipient FROM 2 FOR 3) END AS destination,
                   count(*) AS messages, count(DISTINCT recipient) AS recipients
            FROM notification_message
            WHERE created_at >= :start AND created_at < :end AND erased_at IS NULL
              AND channel IN ('EMAIL', 'SMS', 'WHATSAPP')
            GROUP BY 1, 2, 3""";

    private static final String ERROR_CODES = """
            SELECT client_id, channel, error_code, count(*) AS messages
            FROM notification_message
            WHERE created_at >= :start AND created_at < :end AND status = 'FAILED' AND error_code IS NOT NULL
            GROUP BY client_id, channel, error_code""";

    private static final String LARGEST_REQUEST = """
            SELECT client_id, channel, max(total) AS largest
            FROM notification_request
            WHERE created_at >= :start AND created_at < :end
            GROUP BY client_id, channel""";

    private static final String UPSERT = """
            INSERT INTO traffic_hourly (client_id, channel, hour, messages, recipients, new_recipients, failed,
                                        largest_request, destinations, error_codes, computed_at)
            VALUES (:clientId, :channel, :hour, :messages, :recipients, :newRecipients, :failed, :largestRequest,
                    CAST(:destinations AS jsonb), CAST(:errorCodes AS jsonb), :computedAt)
            ON CONFLICT (client_id, channel, hour) DO UPDATE SET
                messages = EXCLUDED.messages, recipients = EXCLUDED.recipients,
                new_recipients = EXCLUDED.new_recipients, failed = EXCLUDED.failed,
                largest_request = EXCLUDED.largest_request, destinations = EXCLUDED.destinations,
                error_codes = EXCLUDED.error_codes, computed_at = EXCLUDED.computed_at""";

    private record Key(UUID clientId, String channel) {
    }

    private static final class Tally {
        private int messages;
        private int recipients;
        private int newRecipients;
        private int failed;
        private int largestRequest;
        private final Map<String, Integer> destinations = new TreeMap<>();
        private final Map<String, Integer> errorCodes = new TreeMap<>();
    }

    private final JdbcClient source;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Duration newRecipientLookback;
    private final int emailDomainMinRecipients;
    private final ObjectMapper json = new ObjectMapper();

    /**
     * @param source                   reads tenants' messages and requests; may be read-only
     * @param jdbc                     reads and writes {@code traffic_hourly}
     * @param clock                    stamps each measurement
     * @param newRecipientLookback     how long a recipient must have gone without a message to count as new
     * @param emailDomainMinRecipients the fewest recipients an e-mail domain needs to be named
     */
    TrafficAggregator(JdbcClient source, JdbcClient jdbc, Clock clock, Duration newRecipientLookback,
                      int emailDomainMinRecipients) {
        this.source = source;
        this.jdbc = jdbc;
        this.clock = clock;
        this.newRecipientLookback = newRecipientLookback;
        this.emailDomainMinRecipients = emailDomainMinRecipients;
    }

    /**
     * Measures one hour and stores it.
     *
     * @param hour the start of the hour, on a whole UTC hour
     * @return how many tenant and channel rows were written
     */
    int aggregate(Instant hour) {
        List<TrafficHour> measured = measure(hour);
        Timestamp computedAt = Timestamp.from(clock.instant());
        measured.forEach(traffic -> store(traffic, computedAt));
        return measured.size();
    }

    /**
     * Measures one hour without storing it.
     *
     * @param hour the start of the hour, on a whole UTC hour
     * @return one entry per tenant and channel that accepted messages or requests in the hour
     */
    List<TrafficHour> measure(Instant hour) {
        if (!hour.equals(hour.truncatedTo(ChronoUnit.HOURS))) {
            throw new IllegalArgumentException("Not the start of an hour: " + hour);
        }
        Timestamp start = Timestamp.from(hour);
        Timestamp end = Timestamp.from(hour.plus(1, ChronoUnit.HOURS));
        Map<Key, Tally> tallies = new LinkedHashMap<>();

        between(COUNTS, start, end).query((ResultSet row) -> {
            Tally tally = tally(tallies, row);
            tally.messages = row.getInt("messages");
            tally.recipients = row.getInt("recipients");
            tally.failed = row.getInt("failed");
        });
        between(NEW_RECIPIENTS, start, end).param("since", Timestamp.from(hour.minus(newRecipientLookback)))
                .query((ResultSet row) -> {
                    tally(tallies, row).newRecipients = row.getInt("new_recipients");
                });
        between(DESTINATIONS, start, end).query((ResultSet row) -> {
            tally(tallies, row).destinations.merge(destination(row), row.getInt("messages"), Integer::sum);
        });
        between(ERROR_CODES, start, end).query((ResultSet row) -> {
            tally(tallies, row).errorCodes.merge(row.getString("error_code"), row.getInt("messages"), Integer::sum);
        });
        between(LARGEST_REQUEST, start, end).query((ResultSet row) -> {
            tally(tallies, row).largestRequest = row.getInt("largest");
        });

        return tallies.entrySet().stream().map(entry -> {
            Key key = entry.getKey();
            Tally tally = entry.getValue();
            return new TrafficHour(key.clientId(), key.channel(), hour, tally.messages, tally.recipients,
                    tally.newRecipients, tally.failed, tally.largestRequest, Map.copyOf(tally.destinations),
                    Map.copyOf(tally.errorCodes));
        }).toList();
    }

    /**
     * Deletes measurements of hours before a cut-off.
     *
     * @param before the first hour to keep
     * @return how many rows were deleted
     */
    int deleteBefore(Instant before) {
        return jdbc.sql("DELETE FROM traffic_hourly WHERE hour < :before").param("before", Timestamp.from(before)).update();
    }

    /** Whether any hour has been measured yet. */
    boolean hasHistory() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM traffic_hourly)").query(Boolean.class).single();
    }

    private JdbcClient.StatementSpec between(String sql, Timestamp start, Timestamp end) {
        return source.sql(sql).param("start", start).param("end", end);
    }

    private static Tally tally(Map<Key, Tally> tallies, ResultSet row) throws SQLException {
        Key key = new Key(row.getObject("client_id", UUID.class), row.getString("channel"));
        return tallies.computeIfAbsent(key, ignored -> new Tally());
    }

    private String destination(ResultSet row) throws SQLException {
        String destination = row.getString("destination");
        if ("EMAIL".equals(row.getString("channel"))) {
            return row.getInt("recipients") >= emailDomainMinRecipients ? destination : OTHER_DOMAIN;
        }
        return CallingCodes.of(destination);
    }

    private void store(TrafficHour traffic, Timestamp computedAt) {
        jdbc.sql(UPSERT)
                .param("clientId", traffic.clientId())
                .param("channel", traffic.channel())
                .param("hour", Timestamp.from(traffic.hour()))
                .param("messages", traffic.messages())
                .param("recipients", traffic.recipients())
                .param("newRecipients", traffic.newRecipients())
                .param("failed", traffic.failed())
                .param("largestRequest", traffic.largestRequest())
                .param("destinations", toJson(traffic.destinations()))
                .param("errorCodes", toJson(traffic.errorCodes()))
                .param("computedAt", computedAt)
                .update();
    }

    private String toJson(Map<String, Integer> counts) {
        try {
            return json.writeValueAsString(new TreeMap<>(counts));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Counts could not be written as JSON", e);
        }
    }
}
