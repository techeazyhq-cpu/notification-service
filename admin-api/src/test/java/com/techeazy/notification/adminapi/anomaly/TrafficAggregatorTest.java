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

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class TrafficAggregatorTest {

    private static final Instant HOUR = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant COMPUTED_AT = Instant.parse("2026-09-01T11:05:00Z");
    private static final String REPORTING_ROLE = "notification_reporting";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static TrafficAggregator aggregator;

    @BeforeAll
    static void migrate() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
                POSTGRES.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("../db-migration/src/main/resources")), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("CREATE ROLE " + REPORTING_ROLE + " LOGIN PASSWORD '" + REPORTING_ROLE + "'").update();
        jdbc.sql("GRANT USAGE ON SCHEMA public TO " + REPORTING_ROLE).update();
        jdbc.sql("GRANT SELECT ON ALL TABLES IN SCHEMA public TO " + REPORTING_ROLE).update();
        jdbc.sql("ALTER ROLE " + REPORTING_ROLE + " SET default_transaction_read_only = on").update();
        JdbcClient readOnly = JdbcClient.create(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), REPORTING_ROLE,
                REPORTING_ROLE));
        aggregator = new TrafficAggregator(readOnly, jdbc, Clock.fixed(COMPUTED_AT, ZoneOffset.UTC),
                Duration.ofDays(30), 5);
    }

    @Test
    void countsMessagesRecipientsFailuresDestinationsAndTheLargestRequest() {
        UUID client = client();
        UUID bulk = request(client, "SMS", 3, HOUR.plusSeconds(600));
        message(client, bulk, "SMS", "+447700900001", "fp-1", "SENT", null, HOUR.plusSeconds(600));
        message(client, bulk, "SMS", "+447700900002", "fp-2", "FAILED", "PROVIDER_REJECTED", HOUR.plusSeconds(600));
        message(client, bulk, "SMS", "+14155550101", "fp-3", "SENT", null, HOUR.plusSeconds(3599));
        message(client, request(client, "SMS", 1, HOUR.minusSeconds(1)), "SMS", "+447700900009", "fp-9", "SENT", null,
                HOUR.minusSeconds(1));
        message(client, request(client, "SMS", 1, HOUR.plusSeconds(3600)), "SMS", "+447700900008", "fp-8", "SENT",
                null, HOUR.plusSeconds(3600));

        TrafficHour traffic = measured(client, "SMS");

        assertThat(traffic.hour()).isEqualTo(HOUR);
        assertThat(traffic.messages()).isEqualTo(3);
        assertThat(traffic.recipients()).isEqualTo(3);
        assertThat(traffic.failed()).isEqualTo(1);
        assertThat(traffic.largestRequest()).isEqualTo(3);
        assertThat(traffic.destinations()).isEqualTo(Map.of("44", 2, "1", 1));
        assertThat(traffic.errorCodes()).isEqualTo(Map.of("PROVIDER_REJECTED", 1));
    }

    @Test
    void aRecipientIsNewOnlyIfTheTenantHadNotSentToItWithinTheLookBack() {
        UUID client = client();
        UUID earlier = request(client, "SMS", 1, HOUR.minus(Duration.ofDays(2)));
        message(client, earlier, "SMS", "+447700900001", "fp-recent", "SENT", null, HOUR.minus(Duration.ofDays(2)));
        message(client, earlier, "SMS", "+447700900002", "fp-long-ago", "SENT", null, HOUR.minus(Duration.ofDays(40)));
        UUID now = request(client, "SMS", 3, HOUR.plusSeconds(60));
        message(client, now, "SMS", "+447700900001", "fp-recent", "SENT", null, HOUR.plusSeconds(60));
        message(client, now, "SMS", "+447700900002", "fp-long-ago", "SENT", null, HOUR.plusSeconds(60));
        message(client, now, "SMS", "+447700900003", "fp-first-time", "SENT", null, HOUR.plusSeconds(60));

        assertThat(measured(client, "SMS").newRecipients()).isEqualTo(2);
    }

    @Test
    void anotherTenantsRecipientsDoNotMakeOneOld() {
        UUID other = client();
        message(other, request(other, "SMS", 1, HOUR.minus(Duration.ofDays(1))), "SMS", "+447700900001", "fp-shared",
                "SENT", null, HOUR.minus(Duration.ofDays(1)));
        UUID client = client();
        message(client, request(client, "SMS", 1, HOUR.plusSeconds(60)), "SMS", "+447700900001", "fp-shared", "SENT",
                null, HOUR.plusSeconds(60));

        assertThat(measured(client, "SMS").newRecipients()).isEqualTo(1);
    }

    @Test
    void smallEmailDomainsAreMergedSoNoneNamesAPerson() {
        UUID client = client();
        UUID bulk = request(client, "EMAIL", 6, HOUR.plusSeconds(60));
        for (int i = 1; i <= 5; i++) {
            message(client, bulk, "EMAIL", "user" + i + "@Example.com", "fp-" + i, "SENT", null, HOUR.plusSeconds(60));
        }
        message(client, bulk, "EMAIL", "ann@ann-smith.net", "fp-ann", "SENT", null, HOUR.plusSeconds(60));

        assertThat(measured(client, "EMAIL").destinations())
                .isEqualTo(Map.of("example.com", 5, TrafficAggregator.OTHER_DOMAIN, 1));
    }

    @Test
    void anErasedMessageIsCountedButHasNoDestination() {
        UUID client = client();
        UUID id = message(client, request(client, "SMS", 1, HOUR.plusSeconds(60)), "SMS", "[erased]", "fp-1", "SENT",
                null, HOUR.plusSeconds(60));
        jdbc.sql("UPDATE notification_message SET erased_at = :at WHERE id = :id")
                .param("at", Timestamp.from(HOUR.plusSeconds(120))).param("id", id).update();

        TrafficHour traffic = measured(client, "SMS");

        assertThat(traffic.messages()).isEqualTo(1);
        assertThat(traffic.destinations()).isEmpty();
    }

    @Test
    void pushHasVolumeButNoDestinations() {
        UUID client = client();
        message(client, request(client, "PUSH", 1, HOUR.plusSeconds(60)), "PUSH", "device-token-1", "fp-1", "SENT",
                null, HOUR.plusSeconds(60));

        TrafficHour traffic = measured(client, "PUSH");

        assertThat(traffic.messages()).isEqualTo(1);
        assertThat(traffic.destinations()).isEmpty();
    }

    @Test
    void measuringAnHourAgainReplacesItsRow() {
        UUID client = client();
        UUID bulk = request(client, "SMS", 2, HOUR.plusSeconds(60));
        UUID first = message(client, bulk, "SMS", "+447700900001", "fp-1", "RETRYING", null, HOUR.plusSeconds(60));
        aggregator.aggregate(HOUR);
        jdbc.sql("UPDATE notification_message SET status = 'FAILED', error_code = 'PROVIDER_REJECTED' WHERE id = :id")
                .param("id", first).update();
        message(client, bulk, "SMS", "+447700900002", "fp-2", "SENT", null, HOUR.plusSeconds(90));

        aggregator.aggregate(HOUR);

        Map<String, Object> row = jdbc.sql("SELECT messages, failed, error_codes::text AS error_codes, computed_at "
                        + "FROM traffic_hourly WHERE client_id = :client AND channel = 'SMS' AND hour = :hour")
                .param("client", client).param("hour", Timestamp.from(HOUR)).query().singleRow();
        assertThat(row).containsEntry("messages", 2).containsEntry("failed", 1)
                .containsEntry("error_codes", "{\"PROVIDER_REJECTED\": 1}")
                .containsEntry("computed_at", Timestamp.from(COMPUTED_AT));
    }

    @Test
    void anHourMustStartOnTheHour() {
        Instant notOnTheHour = HOUR.plusSeconds(1);

        assertThatThrownBy(() -> aggregator.measure(notOnTheHour)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deletingRemovesOnlyHoursBeforeTheCutOff() {
        UUID client = client();
        Instant old = Instant.parse("2020-01-01T00:00:00Z");
        Instant kept = Instant.parse("2020-01-03T00:00:00Z");
        message(client, request(client, "SMS", 1, old), "SMS", "+447700900001", "fp-1", "SENT", null, old);
        message(client, request(client, "SMS", 1, kept), "SMS", "+447700900001", "fp-1", "SENT", null, kept);
        aggregator.aggregate(old);
        aggregator.aggregate(kept);

        aggregator.deleteBefore(Instant.parse("2020-01-02T00:00:00Z"));

        assertThat(jdbc.sql("SELECT hour FROM traffic_hourly WHERE client_id = :client").param("client", client)
                .query(Timestamp.class).list()).containsExactly(Timestamp.from(kept));
        assertThat(aggregator.hasHistory()).isTrue();
    }

    private static TrafficHour measured(UUID client, String channel) {
        return aggregator.measure(HOUR).stream()
                .filter(traffic -> traffic.clientId().equals(client) && traffic.channel().equals(channel))
                .findFirst().orElseThrow();
    }

    private static UUID client() {
        UUID id = UUID.randomUUID();
        String hash = id.toString().replace("-", "");
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'EMAIL,SMS,WHATSAPP,PUSH', :now, :now)")
                .param("id", id).param("name", "c-" + id).param("hash", hash + hash)
                .param("now", Timestamp.from(HOUR)).update();
        return id;
    }

    private static UUID request(UUID client, String channel, int total, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, body, total, created_at) "
                        + "VALUES (:id, :client, 'BULK', :channel, 'x', :total, :at)")
                .param("id", id).param("client", client).param("channel", channel).param("total", total)
                .param("at", Timestamp.from(at)).update();
        return id;
    }

    private static UUID message(UUID client, UUID request, String channel, String recipient, String fingerprint,
                                String status, String errorCode, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_message (id, request_id, client_id, channel, recipient, recipient_fingerprint, "
                        + "variables, status, error_code, created_at, updated_at) "
                        + "VALUES (:id, :request, :client, :channel, :recipient, :fingerprint, '{}'::jsonb, :status, "
                        + ":errorCode, :at, :at)")
                .param("id", id).param("request", request).param("client", client).param("channel", channel)
                .param("recipient", recipient).param("fingerprint", fingerprint).param("status", status)
                .param("errorCode", errorCode).param("at", Timestamp.from(at)).update();
        return id;
    }
}
