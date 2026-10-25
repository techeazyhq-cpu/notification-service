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

import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcClientDraftingPoliciesTest {

    private static final Instant NOW = Instant.parse("2026-10-25T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static JdbcClientDraftingPolicies policies;

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
        policies = new JdbcClientDraftingPolicies(jdbc, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at,
                                    updated_at)
                VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'SMS', :at, :at)
                """)
                .param("id", id)
                .param("name", "client-" + id)
                .param("hash", id.toString().replace("-", ""))
                .param("at", Timestamp.from(NOW))
                .update();
        return id;
    }

    @Test
    void aClientWithoutASavedPolicyHasTheDefault() {
        assertThat(policies.of(newClient())).isEqualTo(ClientDraftingPolicy.DEFAULT);
    }

    @Test
    void aSavedPolicyIsReadBackAndReplacedBySavingAgain() {
        UUID client = newClient();

        policies.save(client, new ClientDraftingPolicy(false, 5000L), "alice");
        assertThat(policies.of(client)).isEqualTo(new ClientDraftingPolicy(false, 5000L));

        policies.save(client, new ClientDraftingPolicy(true, null), "bob");
        assertThat(policies.of(client)).isEqualTo(new ClientDraftingPolicy(true, null));
        assertThat(policies.all()).containsEntry(client, new ClientDraftingPolicy(true, null));
        assertThat(jdbc.sql("SELECT updated_by FROM client_drafting_policy WHERE client_id = :c").param("c", client)
                .query(String.class).single()).isEqualTo("bob");
    }

    @Test
    void aPolicyNeedsAnExistingClientAndGoesWithIt() {
        assertThatThrownBy(() -> policies.save(UUID.randomUUID(), ClientDraftingPolicy.DEFAULT, "alice"))
                .isInstanceOf(DataAccessException.class);

        UUID client = newClient();
        policies.save(client, new ClientDraftingPolicy(false, null), "alice");
        jdbc.sql("DELETE FROM client WHERE id = :c").param("c", client).update();

        assertThat(policies.all()).doesNotContainKey(client);
    }

    @Test
    void listingThePoliciesGivesItsConnectionBack() {
        try (HikariDataSource onePooledConnection = onePooledConnection()) {
            JdbcClientDraftingPolicies pooled =
                    new JdbcClientDraftingPolicies(JdbcClient.create(onePooledConnection), Clock.systemUTC());

            for (int listing = 0; listing < 3; listing++) {
                assertThat(pooled.all()).isNotNull();
            }
        }
    }

    private static HikariDataSource onePooledConnection() {
        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        dataSource.setMaximumPoolSize(1);
        dataSource.setConnectionTimeout(1000);
        return dataSource;
    }
}
