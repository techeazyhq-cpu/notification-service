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

import com.techeazy.notification.domain.Channel;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * The anomaly list, decisions, open counts, hourly series and weekly report against a real PostgreSQL migrated by the
 * real changelog; skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class AnomalyReadModelTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:30:00Z");
    private static final Instant HOUR = Instant.parse("2026-09-01T09:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static AnomalyRepository repository;
    private static AnomalyStore store;
    private static TrafficReports reports;

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
        repository = new AnomalyRepository(jdbc);
        store = new AnomalyStore(jdbc);
        AnomalyProperties.Scoring scoring = new AnomalyProperties.Scoring();
        reports = new TrafficReports(new TrafficReader(jdbc), repository, new BaselineScorer(scoring),
                Clock.fixed(NOW, ZoneOffset.UTC), scoring.getBaselineWeeks());
    }

    @Test
    void searchNarrowsByEveryFilterNamesTheTenantAndPagesNewestHourFirst() {
        UUID client = client("Acme");
        anomaly(client, "SMS", HOUR.minus(Duration.ofHours(2)), Feature.MESSAGES, Severity.WARNING);
        anomaly(client, "SMS", HOUR, Feature.MESSAGES, Severity.HIGH);
        anomaly(client, "EMAIL", HOUR.minus(Duration.ofHours(1)), Feature.MESSAGES, Severity.HIGH);
        anomaly(client("Other"), "SMS", HOUR, Feature.MESSAGES, Severity.HIGH);

        AnomalyRepository.Page all = repository.search(new AnomalyRepository.Query(null, client, null, null), 0, 2);
        AnomalyRepository.Page rest = repository.search(new AnomalyRepository.Query(null, client, null, null), 1, 2);
        AnomalyRepository.Page highSms = repository.search(
                new AnomalyRepository.Query(AnomalyStatus.OPEN, client, "SMS", Severity.HIGH), 0, 10);

        assertThat(all.totalItems()).isEqualTo(3);
        assertThat(all.items()).extracting(AnomalyView::hour).containsExactly(HOUR, HOUR.minus(Duration.ofHours(1)));
        assertThat(all.items()).extracting(AnomalyView::clientName).allMatch(name -> name.startsWith("Acme-"));
        assertThat(rest.items()).extracting(AnomalyView::hour).containsExactly(HOUR.minus(Duration.ofHours(2)));
        assertThat(highSms.items()).singleElement().satisfies(anomaly -> {
            assertThat(anomaly.channel()).isEqualTo("SMS");
            assertThat(anomaly.severity()).isEqualTo(Severity.HIGH);
            assertThat(anomaly.status()).isEqualTo(AnomalyStatus.OPEN);
            assertThat(anomaly.expected()).isEqualTo(100);
            assertThat(anomaly.actual()).isEqualTo(900);
        });
    }

    @Test
    void aDecisionRecordsWhoWhenAndWhyAndCanBeChangedButNeverReopened() {
        UUID client = client("Acme");
        anomaly(client, "SMS", HOUR, Feature.MESSAGES, Severity.HIGH);
        UUID id = repository.search(new AnomalyRepository.Query(null, client, null, null), 0, 1).items().getFirst().id();

        AnomalyView confirmed = repository.resolve(id, AnomalyStatus.CONFIRMED, "alice", "pumping to +882", NOW).orElseThrow();
        AnomalyView changed = repository.resolve(id, AnomalyStatus.FALSE_ALARM, "bob", null, NOW.plusSeconds(60)).orElseThrow();

        assertThat(confirmed.status()).isEqualTo(AnomalyStatus.CONFIRMED);
        assertThat(confirmed.resolvedBy()).isEqualTo("alice");
        assertThat(confirmed.resolutionNote()).isEqualTo("pumping to +882");
        assertThat(confirmed.resolvedAt()).isEqualTo(NOW);
        assertThat(changed.status()).isEqualTo(AnomalyStatus.FALSE_ALARM);
        assertThat(changed.resolvedBy()).isEqualTo("bob");
        assertThat(changed.resolutionNote()).isNull();
        assertThatThrownBy(() -> repository.resolve(id, AnomalyStatus.OPEN, "carol", null, NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(repository.resolve(UUID.randomUUID(), AnomalyStatus.CONFIRMED, "alice", null, NOW)).isEmpty();
    }

    @Test
    void openCountsAreByChannelAndSeverityAndLeaveOutDecidedAnomalies() {
        Map<String, Long> before = openCounts();
        UUID client = client("Acme");
        anomaly(client, "WHATSAPP", HOUR, Feature.MESSAGES, Severity.HIGH);
        anomaly(client, "WHATSAPP", HOUR, Feature.NEW_DESTINATION, Severity.HIGH);
        anomaly(client, "WHATSAPP", HOUR, Feature.LARGEST_REQUEST, Severity.WARNING);
        anomaly(client, "WHATSAPP", HOUR, Feature.NEW_RECIPIENTS, Severity.WARNING);
        UUID decided = repository.search(new AnomalyRepository.Query(null, client, null, Severity.WARNING), 0, 1)
                .items().getFirst().id();
        repository.resolve(decided, AnomalyStatus.FALSE_ALARM, "alice", null, NOW);

        Map<String, Long> after = openCounts();

        assertThat(after.getOrDefault("WHATSAPP/HIGH", 0L) - before.getOrDefault("WHATSAPP/HIGH", 0L)).isEqualTo(2);
        assertThat(after.getOrDefault("WHATSAPP/WARNING", 0L) - before.getOrDefault("WHATSAPP/WARNING", 0L)).isEqualTo(1);
    }

    @Test
    void theSeriesHasEveryHourWithTheBaselineTheScorerUsesAndThePeriodsAnomalies() {
        UUID client = client("Acme");
        for (int week = 1; week <= 8; week++) {
            traffic(client, "SMS", HOUR.minus(Duration.ofDays(7L * week)), 1000, 0, 1, Map.of());
        }
        traffic(client, "SMS", HOUR, 5000, 25, 1, Map.of());
        anomaly(client, "SMS", HOUR, Feature.MESSAGES, Severity.HIGH);
        anomaly(client, "SMS", HOUR.minus(Duration.ofDays(3)), Feature.MESSAGES, Severity.HIGH);

        TrafficReports.Series series = reports.series(client, Channel.SMS, 1).orElseThrow();

        assertThat(series.clientName()).startsWith("Acme-");
        assertThat(series.to()).isEqualTo(Instant.parse("2026-09-01T10:00:00Z"));
        assertThat(series.points()).hasSize(24);
        assertThat(series.points().getLast()).isEqualTo(new TrafficReports.Point(HOUR, 5000, 25, 0, 1000.0));
        assertThat(series.points().get(22)).isEqualTo(
                new TrafficReports.Point(HOUR.minus(Duration.ofHours(1)), 0, 0, 0, 0.0));
        assertThat(series.anomalies()).singleElement().extracting(AnomalyView::hour).isEqualTo(HOUR);
    }

    @Test
    void aTenantInItsColdStartHasNoBaselineInTheSeries() {
        UUID client = client("New");
        traffic(client, "SMS", HOUR.minus(Duration.ofDays(3)), 10, 0, 1, Map.of());

        TrafficReports.Series series = reports.series(client, Channel.SMS, 1).orElseThrow();

        assertThat(series.points()).extracting(TrafficReports.Point::expected).containsOnlyNulls();
    }

    @Test
    void anUnknownTenantHasNoSeriesAndNoReport() {
        assertThat(reports.series(UUID.randomUUID(), Channel.SMS, 1)).isEmpty();
        assertThat(reports.weekly(UUID.randomUUID(), LocalDate.parse("2026-08-24"))).isEmpty();
    }

    @Test
    void theWeeklyReportSumsEachChannelAndCountsItsAnomaliesAndDecisions() {
        UUID client = client("Acme");
        traffic(client, "SMS", Instant.parse("2026-08-24T10:00:00Z"), 100, 10, 50, Map.of("44", 90, "91", 10));
        traffic(client, "SMS", Instant.parse("2026-08-25T12:00:00Z"), 300, 0, 300, Map.of("44", 300));
        traffic(client, "EMAIL", Instant.parse("2026-08-26T09:00:00Z"), 40, 0, 40, Map.of("example.com", 40));
        traffic(client, "SMS", Instant.parse("2026-08-31T00:00:00Z"), 9999, 0, 1, Map.of());
        anomaly(client, "SMS", Instant.parse("2026-08-25T12:00:00Z"), Feature.MESSAGES, Severity.HIGH);
        anomaly(client, "SMS", Instant.parse("2026-08-24T10:00:00Z"), Feature.FAILURE_RATE, Severity.WARNING);
        UUID warning = repository.search(new AnomalyRepository.Query(null, client, null, Severity.WARNING), 0, 1)
                .items().getFirst().id();
        repository.resolve(warning, AnomalyStatus.FALSE_ALARM, "alice", null, NOW);

        TrafficReports.WeeklyReport report = reports.weekly(client, reports.lastCompleteWeek()).orElseThrow();

        assertThat(report.weekStart()).isEqualTo(LocalDate.parse("2026-08-24"));
        assertThat(report.channels()).extracting(TrafficReports.ChannelWeek::channel).containsExactly("EMAIL", "SMS");
        TrafficReports.ChannelWeek sms = report.channels().get(1);
        assertThat(sms.messages()).isEqualTo(400);
        assertThat(sms.failed()).isEqualTo(10);
        assertThat(sms.failureRate()).isCloseTo(0.025, within(1e-9));
        assertThat(sms.peakHour()).isEqualTo(Instant.parse("2026-08-25T12:00:00Z"));
        assertThat(sms.peakHourMessages()).isEqualTo(300);
        assertThat(sms.largestRequest()).isEqualTo(300);
        assertThat(sms.topDestinations()).containsExactly(Map.entry("44", 390L), Map.entry("91", 10L));
        assertThat(List.of(sms.anomalies(), sms.high(), sms.confirmed(), sms.falseAlarms(), sms.open()))
                .containsExactly(2L, 1L, 0L, 1L, 1L);
        assertThat(report.channels().getFirst().anomalies()).isZero();
    }

    @Test
    void aWeekMustStartOnAMonday() {
        UUID client = client("Acme");
        LocalDate tuesday = LocalDate.parse("2026-08-25");

        assertThatThrownBy(() -> reports.weekly(client, tuesday)).isInstanceOf(IllegalArgumentException.class);
    }

    private static Map<String, Long> openCounts() {
        Map<String, Long> counts = new HashMap<>();
        repository.openCounts().forEach(count -> counts.put(count.channel() + "/" + count.severity(), count.count()));
        return counts;
    }

    private static UUID client(String name) {
        UUID id = UUID.randomUUID();
        String hash = id.toString().replace("-", "");
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'EMAIL,SMS,WHATSAPP,PUSH', :now, :now)")
                .param("id", id).param("name", name + "-" + id).param("hash", hash + hash)
                .param("now", Timestamp.from(NOW)).update();
        return id;
    }

    private static void anomaly(UUID client, String channel, Instant hour, Feature feature, Severity severity) {
        store.record(List.of(new Anomaly(client, channel, hour, feature, DetectionMethod.BASELINE, 100, 900, 9.5,
                severity, null)), NOW);
    }

    private static void traffic(UUID client, String channel, Instant hour, int messages, int failed, int largestRequest,
                                Map<String, Integer> destinations) {
        String json = destinations.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\": " + entry.getValue())
                .reduce((left, right) -> left + ", " + right).map(body -> "{" + body + "}").orElse("{}");
        jdbc.sql("INSERT INTO traffic_hourly (client_id, channel, hour, messages, recipients, new_recipients, failed, "
                        + "largest_request, destinations, computed_at) VALUES (:client, :channel, :hour, :messages, "
                        + ":messages, 0, :failed, :largest, CAST(:destinations AS jsonb), :hour)")
                .param("client", client).param("channel", channel).param("hour", Timestamp.from(hour))
                .param("messages", messages).param("failed", failed).param("largest", largestRequest)
                .param("destinations", json).update();
    }
}
