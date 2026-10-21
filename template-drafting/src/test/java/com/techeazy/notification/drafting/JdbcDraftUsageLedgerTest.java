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

import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.drafting.DraftUsageLedger.Outcome;
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
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL migrated by the real changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class JdbcDraftUsageLedgerTest {

    private static final Instant MONTH_START = Instant.parse("2026-10-01T00:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static JdbcDraftUsageLedger ledger;

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
        ledger = new JdbcDraftUsageLedger(jdbc);
    }

    private static DraftUsageLedger.Usage usage(UUID clientId, Instant at, long in, long out) {
        return new DraftUsageLedger.Usage(UUID.randomUUID(), at, clientId == null ? "alice" : "acme", clientId,
                Channel.EMAIL, "claude-opus-5-5", in, out, Outcome.DRAFTED);
    }

    @Test
    void tokensAreSummedPerClientAndForEveryoneFromTheGivenInstant() {
        UUID client = UUID.randomUUID();
        Instant since = MONTH_START.plusSeconds(86_400);
        ledger.record(usage(client, since.minusSeconds(1), 1000, 1000));
        ledger.record(usage(client, since, 300, 200));
        ledger.record(usage(client, since.plusSeconds(60), 100, 50));
        ledger.record(usage(null, since.plusSeconds(120), 70, 30));

        assertThat(ledger.tokensSince(client, since)).isEqualTo(650);
        assertThat(ledger.tokensSince(since)).isEqualTo(750);
        assertThat(ledger.tokensSince(UUID.randomUUID(), since)).isZero();
    }

    @Test
    void theLedgerIsAppendOnly() {
        UUID client = UUID.randomUUID();
        ledger.record(usage(client, MONTH_START, 1, 1));

        assertThatThrownBy(() -> jdbc.sql("UPDATE template_draft_usage SET input_tokens = 0 WHERE client_id = :c")
                .param("c", client).update()).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM template_draft_usage WHERE client_id = :c")
                .param("c", client).update()).isInstanceOf(DataAccessException.class);
    }
}
