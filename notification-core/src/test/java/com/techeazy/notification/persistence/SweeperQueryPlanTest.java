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
package com.techeazy.notification.persistence;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the sweeper's queries against a real PostgreSQL holding a large backlog. Each sweep must read its rows in
 * order from an index and stop at the batch size, never sort the whole backlog, including under the generic plan the
 * driver switches to once a statement has been prepared a few times. Skipped without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class SweeperQueryPlanTest {

    private static final int BACKLOG = 50_000;
    private static final Instant NOW = Instant.parse("2026-10-02T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static UUID clientId;
    private static UUID requestId;

    @BeforeAll
    static void migrateAndFillABacklog() throws Exception {
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance()
                    .findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("../db-migration/src/main/resources")), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = JdbcClient.create(dataSource);
        clientId = UUID.randomUUID();
        requestId = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, "
                        + "updated_at) VALUES (:id, 'acme', :hash, 'ntf_test', 'ACTIVE', 'SMS', :now, :now)")
                .param("id", clientId).param("hash", "h".repeat(64)).param("now", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, body, total, created_at) "
                        + "VALUES (:id, :client, 'BULK', 'SMS', 'Sale', :total, :now)")
                .param("id", requestId).param("client", clientId).param("total", BACKLOG)
                .param("now", Timestamp.from(NOW)).update();
        jdbc.sql("INSERT INTO notification_message (id, request_id, client_id, channel, recipient, variables, status, "
                        + "category, created_at, updated_at) "
                        + "SELECT gen_random_uuid(), :request, :client, 'SMS', '+1', '{}'::jsonb, "
                        + "CASE WHEN g % 2 = 0 THEN 'QUEUED' ELSE 'SENT' END, "
                        + "CASE WHEN g % 10 = 0 THEN 'OTP' ELSE 'PROMOTIONAL' END, :now, "
                        + "CAST(:now AS timestamptz) - make_interval(secs => g) FROM generate_series(1, :count) g")
                .param("request", requestId).param("client", clientId).param("now", Timestamp.from(NOW))
                .param("count", BACKLOG * 2).update();
        jdbc.sql("ANALYZE notification_message").update();
    }

    @Test
    void theGeneralSweepReadsTheStatusIndexInOrderAndSortsNothing() throws Exception {
        List<String> plan = genericPlan("lockStale");

        assertThat(plan).anyMatch(line -> line.contains("ix_message_status_updated"))
                .noneMatch(line -> line.contains("Sort"));
    }

    @Test
    void theOneTimePasswordSweepUsesItsPartialIndexInEveryStatus() throws Exception {
        List<String> plan = genericPlan("lockStaleOtp");

        assertThat(plan).anyMatch(line -> line.contains("ix_message_inflight_otp"))
                .noneMatch(line -> line.contains("Sort"));
    }

    @Test
    void theOneTimePasswordSweepReturnsOnlyStaleOneTimePasswordsOfTheStatusOldestFirst() throws Exception {
        List<Object[]> rows = jdbc.sql(sql("lockStaleOtp"))
                .param("status", "QUEUED").param("cutoff", Timestamp.from(NOW.minusSeconds(60))).param("batch", 5)
                .query((rs, n) -> new Object[] {rs.getString("status"), rs.getString("category"),
                        rs.getTimestamp("updated_at").toInstant()})
                .list();

        assertThat(rows).hasSize(5).allSatisfy(row -> {
            assertThat(row[0]).isEqualTo("QUEUED");
            assertThat(row[1]).isEqualTo("OTP");
            assertThat((Instant) row[2]).isBefore(NOW.minusSeconds(60));
        });
        assertThat(rows.stream().map(row -> (Instant) row[2]).toList()).isSorted();
    }

    /**
     * The plan PostgreSQL uses once the status, cutoff and batch are parameters rather than values it can see, as
     * they are after the JDBC driver switches to a server-side prepared statement.
     */
    private static List<String> genericPlan(String method) throws Exception {
        String prepared = sql(method).replace(":status", "$1").replace(":cutoff", "$2").replace(":batch", "$3");
        List<String> plan = new ArrayList<>();
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("SET plan_cache_mode = force_generic_plan");
            statement.execute("PREPARE sweep(text, timestamptz, int) AS " + prepared);
            try (ResultSet rs = statement.executeQuery(
                    "EXPLAIN EXECUTE sweep('QUEUED', '" + NOW.minusSeconds(60) + "', 500)")) {
                while (rs.next()) {
                    plan.add(rs.getString(1));
                }
            }
        }
        return plan;
    }

    private static String sql(String method) throws Exception {
        return NotificationMessageRepository.class.getMethod(method, String.class, Instant.class, int.class)
                .getAnnotation(Query.class).value();
    }
}
