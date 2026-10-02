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
package com.techeazy.notification.dispatcher;

import com.techeazy.notification.domain.MessageStatus;
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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcBacklogReaderTest {

    private static final Instant NOW = Instant.parse("2026-10-02T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;

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
    }

    private static UUID request() {
        UUID client = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at,
                                    updated_at)
                VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'SMS', now(), now())
                """).param("id", client).param("name", "client-" + client)
                .param("hash", client.toString().replace("-", "")).update();
        UUID request = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification_request (id, client_id, kind, channel, total, created_at)
                VALUES (:id, :client, 'BULK', 'SMS', 10, now())
                """).param("id", request).param("client", client).update();
        return request;
    }

    private static void message(UUID request, MessageStatus status, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO notification_message (id, request_id, client_id, channel, recipient, status, created_at,
                                                  updated_at)
                SELECT :id, id, client_id, 'SMS', '+14155550123', :status, :createdAt, now()
                FROM notification_request WHERE id = :request
                """).param("id", UUID.randomUUID()).param("status", status.name())
                .param("createdAt", Timestamp.from(createdAt)).param("request", request).update();
    }

    @Test
    void countsMessagesStillInFlightAndFindsTheOldestOfEachStatus() {
        UUID request = request();
        message(request, MessageStatus.PENDING, NOW.minusSeconds(30));
        message(request, MessageStatus.QUEUED, NOW.minusSeconds(400));
        message(request, MessageStatus.QUEUED, NOW.minusSeconds(90));
        message(request, MessageStatus.RETRYING, NOW.minusSeconds(1200));
        message(request, MessageStatus.SENT, NOW.minusSeconds(5000));
        message(request, MessageStatus.FAILED, NOW.minusSeconds(9000));

        Map<MessageStatus, BacklogSnapshot> backlog = new JdbcBacklogReader(jdbc).read().stream()
                .collect(Collectors.toMap(BacklogSnapshot::status, snapshot -> snapshot));

        assertThat(backlog).containsOnlyKeys(MessageStatus.PENDING, MessageStatus.QUEUED, MessageStatus.RETRYING);
        assertThat(backlog.get(MessageStatus.QUEUED).messages()).isEqualTo(2);
        assertThat(backlog.get(MessageStatus.QUEUED).oldestCreatedAt()).isEqualTo(NOW.minusSeconds(400));
        assertThat(backlog.get(MessageStatus.RETRYING).oldestCreatedAt()).isEqualTo(NOW.minusSeconds(1200));
    }
}
