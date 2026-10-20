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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class AnomalyDetectorTest {

    private static final Instant HOUR = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant DETECTED_AT = Instant.parse("2026-09-01T11:05:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static TrafficReader reader;
    private static AnomalyDetector detector;

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
        reader = new TrafficReader(jdbc);
        AnomalyProperties.Scoring scoring = new AnomalyProperties.Scoring();
        detector = new AnomalyDetector(reader, new BaselineScorer(scoring), new AnomalyStore(jdbc),
                Clock.fixed(DETECTED_AT, ZoneOffset.UTC), scoring.getBaselineWeeks());
    }

    @Test
    void historyHoldsTheTenantsFirstHourItsSameHourOfWeekAndItsDestinations() {
        UUID client = UUID.randomUUID();
        Instant firstSeen = HOUR.minus(Duration.ofDays(100));
        hour(client, "SMS", firstSeen, 10, Map.of("91", 10));
        hour(client, "SMS", HOUR.minus(Duration.ofDays(7)), 1000, Map.of("44", 1000));
        hour(client, "SMS", HOUR.minus(Duration.ofDays(14)).plus(Duration.ofHours(1)), 999, Map.of("33", 999));
        hour(client, "SMS", HOUR.minus(Duration.ofDays(57)), 5, Map.of("1", 5));
        hour(client, "EMAIL", HOUR.minus(Duration.ofDays(7)), 777, Map.of("example.com", 777));
        hour(UUID.randomUUID(), "SMS", HOUR.minus(Duration.ofDays(7)), 1, Map.of("880", 1));

        TenantHistory history = reader.history(client, "SMS", HOUR, 8);

        assertThat(history.firstSeen()).isEqualTo(firstSeen);
        assertThat(history.sameHourOfWeek()).singleElement().satisfies(past -> {
            assertThat(past.hour()).isEqualTo(HOUR.minus(Duration.ofDays(7)));
            assertThat(past.messages()).isEqualTo(1000);
            assertThat(past.destinations()).isEqualTo(Map.of("44", 1000));
        });
        assertThat(history.knownDestinations()).containsExactlyInAnyOrder("44", "33");
    }

    @Test
    void aSpikeAndANewCountryAreRecordedOnceWithWhatWasExpected() {
        UUID client = steadyTenant();
        hour(client, "SMS", HOUR, 5000, Map.of("44", 4980, "882", 20));

        List<Anomaly> first = detector.detect(HOUR, Feature.IMMEDIATE);
        List<Anomaly> again = detector.detect(HOUR, Feature.IMMEDIATE);

        assertThat(first).filteredOn(anomaly -> anomaly.clientId().equals(client)).extracting(Anomaly::feature)
                .containsExactly(Feature.MESSAGES, Feature.NEW_DESTINATION);
        assertThat(again).filteredOn(anomaly -> anomaly.clientId().equals(client)).isEmpty();
        List<Map<String, Object>> rows = jdbc.sql("SELECT feature, method, expected, actual, severity, detail, status, "
                        + "detected_at FROM traffic_anomaly WHERE client_id = :client ORDER BY feature")
                .param("client", client).query().listOfRows();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("feature", "MESSAGES").containsEntry("method", "BASELINE")
                .containsEntry("expected", 1000.0).containsEntry("actual", 5000.0).containsEntry("severity", "HIGH")
                .containsEntry("status", "OPEN").containsEntry("detected_at", Timestamp.from(DETECTED_AT));
        assertThat(rows.get(1)).containsEntry("feature", "NEW_DESTINATION").containsEntry("detail", "882")
                .containsEntry("actual", 20.0);
    }

    @Test
    void anAdministratorsDecisionSurvivesALaterRun() {
        UUID client = steadyTenant();
        hour(client, "SMS", HOUR, 5000, Map.of("44", 5000));
        detector.detect(HOUR, Feature.IMMEDIATE);
        jdbc.sql("UPDATE traffic_anomaly SET status = 'FALSE_ALARM', resolved_by = 'alice', resolved_at = now() "
                + "WHERE client_id = :client").param("client", client).update();

        detector.detect(HOUR, Feature.IMMEDIATE);

        assertThat(jdbc.sql("SELECT status FROM traffic_anomaly WHERE client_id = :client").param("client", client)
                .query(String.class).list()).containsExactly("FALSE_ALARM");
    }

    @Test
    void anOrdinaryHourRecordsNothing() {
        UUID client = steadyTenant();
        hour(client, "SMS", HOUR, 1050, Map.of("44", 1050));

        detector.detect(HOUR, Feature.IMMEDIATE);

        assertThat(jdbc.sql("SELECT count(*) FROM traffic_anomaly WHERE client_id = :client").param("client", client)
                .query(Integer.class).single()).isZero();
    }

    private static UUID steadyTenant() {
        UUID client = UUID.randomUUID();
        for (int week = 1; week <= 8; week++) {
            hour(client, "SMS", HOUR.minus(Duration.ofDays(7L * week)), 1000, Map.of("44", 1000));
        }
        return client;
    }

    private static void hour(UUID client, String channel, Instant hour, int messages, Map<String, Integer> destinations) {
        String json = destinations.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\": " + entry.getValue())
                .reduce((left, right) -> left + ", " + right).map(body -> "{" + body + "}").orElse("{}");
        jdbc.sql("INSERT INTO traffic_hourly (client_id, channel, hour, messages, recipients, new_recipients, failed, "
                        + "largest_request, destinations, computed_at) VALUES (:client, :channel, :hour, :messages, "
                        + ":messages, 0, 0, 1, CAST(:destinations AS jsonb), :hour)")
                .param("client", client).param("channel", channel).param("hour", Timestamp.from(hour))
                .param("messages", messages).param("destinations", json).update();
    }
}
