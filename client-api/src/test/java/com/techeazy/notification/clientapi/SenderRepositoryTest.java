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

package com.techeazy.notification.clientapi;

import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adapter tests against a real PostgreSQL with the real Liquibase changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class SenderRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;
    private static SenderRepository repository;

    @BeforeAll
    static void migrate() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml",
                    new DirectoryResourceAccessor(Path.of("../db-migration/src/main/resources")), database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = JdbcClient.create(dataSource);
        repository = new SenderRepository(jdbc);
    }

    private static UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'EMAIL', :now, :now)")
                .param("id", id).param("name", "c-" + id).param("hash", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .param("now", Timestamp.from(NOW)).update();
        return id;
    }

    private static SenderAddress pending(UUID clientId, String email) {
        return new SenderAddress(UUID.randomUUID(), clientId, email, "Name", SenderAddress.Status.PENDING, false, NOW, null, NOW);
    }

    @Test
    void aTokenVerifiesTheAddressOnceAndOnlyBeforeItExpires() {
        UUID client = newClient();
        SenderAddress sender = pending(client, "a@acme.com");
        repository.insert(sender, "hash-ok", NOW.plusSeconds(3600));

        assertThat(repository.verifyByToken("hash-ok", NOW.plusSeconds(10))).get().extracting(SenderAddress::status).isEqualTo(SenderAddress.Status.VERIFIED);
        assertThat(repository.verifyByToken("hash-ok", NOW.plusSeconds(11))).isEmpty();
        assertThat(repository.find(client, sender.id()).orElseThrow().verifiedAt()).isNotNull();

        SenderAddress late = pending(client, "late@acme.com");
        repository.insert(late, "hash-late", NOW.plusSeconds(60));
        assertThat(repository.verifyByToken("hash-late", NOW.plusSeconds(61))).isEmpty();
        assertThat(repository.verifyByToken("unknown", NOW)).isEmpty();
    }

    @Test
    void anAddressIsUniquePerClientIgnoringCaseButAnotherClientMayUseIt() {
        UUID first = newClient();
        UUID second = newClient();
        repository.insert(pending(first, "Same@acme.com"), "h1", NOW.plusSeconds(60));

        assertThat(repository.emailExists(first, "same@ACME.com")).isTrue();
        assertThatThrownBy(() -> repository.insert(pending(first, "same@acme.com"), "h2", NOW.plusSeconds(60))).isInstanceOf(DuplicateKeyException.class);
        repository.insert(pending(second, "same@acme.com"), "h3", NOW.plusSeconds(60));
        assertThat(repository.findByClient(second)).hasSize(1);
    }

    @Test
    void onlyVerifiedAddressesAreFoundForSendingAndOnlyOneIsDefault() {
        UUID client = newClient();
        SenderAddress a = pending(client, "a@acme.com");
        SenderAddress b = pending(client, "b@acme.com");
        repository.insert(a, "ha", NOW.plusSeconds(60));
        repository.insert(b, "hb", NOW.plusSeconds(60));

        assertThat(repository.findVerifiedByEmail(client, "a@acme.com")).isEmpty();
        assertThat(repository.markDefault(client, a.id())).isFalse();

        repository.verifyByToken("ha", NOW);
        repository.verifyByToken("hb", NOW);
        assertThat(repository.findVerifiedByEmail(client, "A@ACME.com")).isPresent();

        repository.markDefault(client, a.id());
        assertThat(repository.findVerifiedDefault(client)).get().extracting(SenderAddress::email).isEqualTo("a@acme.com");
        assertThatThrownBy(() -> repository.markDefault(client, b.id())).isInstanceOf(DuplicateKeyException.class);

        repository.clearDefault(client);
        repository.markDefault(client, b.id());
        assertThat(repository.findVerifiedDefault(client)).get().extracting(SenderAddress::email).isEqualTo("b@acme.com");
    }

    @Test
    void replacingTheTokenInvalidatesTheOldOneAndDeleteIsScopedToTheClient() {
        UUID client = newClient();
        SenderAddress sender = pending(client, "r@acme.com");
        repository.insert(sender, "old", NOW.plusSeconds(60));

        repository.replaceToken(sender.id(), "new", NOW.plusSeconds(120), NOW.plusSeconds(5));

        assertThat(repository.verifyByToken("old", NOW)).isEmpty();
        assertThat(repository.find(client, sender.id()).orElseThrow().verificationSentAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(repository.delete(UUID.randomUUID(), sender.id())).isFalse();
        assertThat(repository.delete(client, sender.id())).isTrue();
        assertThat(repository.find(client, sender.id())).isEmpty();
    }
}
