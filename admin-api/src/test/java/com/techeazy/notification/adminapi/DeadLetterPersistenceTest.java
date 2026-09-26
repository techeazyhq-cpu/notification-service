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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.application.PersonalData;
import com.techeazy.notification.config.CoreConfig;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.FailureKind;
import com.techeazy.notification.domain.MessageStatus;
import com.techeazy.notification.domain.NotificationMessage;
import com.techeazy.notification.persistence.NotificationMessageRepository;
import jakarta.persistence.EntityManager;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The dead-letter queries and status changes against real PostgreSQL with the real changelog and the real entity mapping. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.liquibase.enabled=false"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({CoreConfig.class, DeadLetterPersistenceTest.EncryptionForTest.class})
@EnableAutoConfiguration
class DeadLetterPersistenceTest {

    @org.springframework.boot.test.context.TestConfiguration
    static class EncryptionForTest {
        @org.springframework.context.annotation.Bean
        com.techeazy.notification.domain.FieldEncryptor fieldEncryptor() {
            return new com.techeazy.notification.infra.AesGcmCipher("test-data-key");
        }
    }


    private static final Instant NOW = Instant.parse("2026-09-20T10:00:00Z");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PG::getJdbcUrl);
        registry.add("spring.datasource.username", PG::getUsername);
        registry.add("spring.datasource.password", PG::getPassword);
    }

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
    }

    @Autowired NotificationMessageRepository messages;
    @Autowired EntityManager em;
    @Autowired JdbcClient jdbc;

    private UUID client() {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at) "
                        + "VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'SMS', :now, :now)")
                .param("id", id).param("name", "c-" + id).param("hash", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .param("now", Timestamp.from(NOW)).update();
        return id;
    }

    private UUID request(UUID client) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, body, total, created_at) VALUES (:id, :client, 'SINGLE', 'SMS', 'x', 1, :now)")
                .param("id", id).param("client", client).param("now", Timestamp.from(NOW)).update();
        return id;
    }

    private UUID message(UUID client, MessageStatus status, FailureKind kind, String recipient, int minutesAgo) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_message (id, request_id, client_id, channel, recipient, variables, status, failure_kind, last_error, created_at, updated_at) "
                        + "VALUES (:id, :request, :client, 'SMS', :recipient, '{}'::jsonb, :status, :kind, 'some error', :at, :at)")
                .param("id", id).param("request", request(client)).param("client", client).param("recipient", recipient)
                .param("status", status.name()).param("kind", kind == null ? null : kind.name())
                .param("at", Timestamp.from(NOW.minusSeconds(minutesAgo * 60L))).update();
        return id;
    }

    private NotificationMessage reload(UUID id) {
        em.flush();
        em.clear();
        return messages.findById(id).orElseThrow();
    }

    @Test
    void aFailureIsStoredWithItsKindAndClearedWhenTheMessageIsFinallySent() {
        UUID client = client();
        UUID id = message(client, MessageStatus.PROCESSING, null, "+1", 1);

        messages.markFailed(id, FailureKind.EXHAUSTED, "Gave up after 5 attempts", NOW);
        NotificationMessage failed = reload(id);
        assertThat(failed.getStatus()).isEqualTo(MessageStatus.FAILED);
        assertThat(failed.getFailureKind()).isEqualTo(FailureKind.EXHAUSTED);

        messages.requeueFailed(id, NOW);
        NotificationMessage requeued = reload(id);
        assertThat(requeued.getStatus()).isEqualTo(MessageStatus.PENDING);
        assertThat(requeued.getFailureKind()).isNull();
        assertThat(requeued.getReprocessCount()).isEqualTo(1);
        assertThat(requeued.getAttempts()).isZero();

        messages.markFailed(id, FailureKind.EXHAUSTED, "again", NOW);
        messages.requeueFailed(id, NOW);
        assertThat(reload(id).getReprocessCount()).isEqualTo(2);

        messages.markSent(id, "provider-1", NOW);
        assertThat(reload(id).getFailureKind()).isNull();
    }

    @Test
    void aBrokerDeadLetterFailsOnlyMessagesStillInFlight() {
        UUID client = client();
        UUID queued = message(client, MessageStatus.QUEUED, null, "+1", 1);
        UUID retrying = message(client, MessageStatus.RETRYING, null, "+2", 1);
        UUID sent = message(client, MessageStatus.SENT, null, "+3", 1);
        UUID failed = message(client, MessageStatus.FAILED, FailureKind.PERMANENT, "+4", 1);

        assertThat(messages.markDeadLettered(queued, MessageStatus.IN_FLIGHT, "gave up", NOW)).isEqualTo(1);
        assertThat(messages.markDeadLettered(retrying, MessageStatus.IN_FLIGHT, "gave up", NOW)).isEqualTo(1);
        assertThat(messages.markDeadLettered(sent, MessageStatus.IN_FLIGHT, "gave up", NOW)).isZero();
        assertThat(messages.markDeadLettered(failed, MessageStatus.IN_FLIGHT, "gave up", NOW)).isZero();

        assertThat(reload(queued).getFailureKind()).isEqualTo(FailureKind.DEAD_LETTERED);
        assertThat(reload(queued).getLastError()).isEqualTo("gave up");
        assertThat(reload(sent).getStatus()).isEqualTo(MessageStatus.SENT);
        assertThat(reload(failed).getFailureKind()).isEqualTo(FailureKind.PERMANENT);
    }

    @Test
    void theViewListsEveryFailureButReprocessingSkipsPermanentAndErasedOnes() {
        UUID client = client();
        UUID exhausted = message(client, MessageStatus.FAILED, FailureKind.EXHAUSTED, "+10", 3);
        UUID deadLettered = message(client, MessageStatus.FAILED, FailureKind.DEAD_LETTERED, "+11", 2);
        UUID permanent = message(client, MessageStatus.FAILED, FailureKind.PERMANENT, "+12", 4);
        UUID legacy = message(client, MessageStatus.FAILED, null, "+13", 1);
        UUID erased = message(client, MessageStatus.FAILED, FailureKind.EXHAUSTED, PersonalData.ERASED, 5);
        message(client, MessageStatus.SENT, null, "+14", 1);
        message(client, MessageStatus.QUEUED, null, "+15", 1);
        em.flush();
        em.clear();

        DeadLetterFilter view = new DeadLetterFilter(client, null, null, null, false);
        DeadLetterFilter safe = new DeadLetterFilter(client, null, null, null, true);
        DeadLetterFilter includePermanent = new DeadLetterFilter(client, null, null, null, false);

        assertThat(ids(messages.findAll(view.viewSpecification()))).containsExactlyInAnyOrder(exhausted, deadLettered, permanent, legacy, erased);
        assertThat(ids(messages.findAll(safe.reprocessSpecification()))).containsExactlyInAnyOrder(exhausted, deadLettered, legacy);
        assertThat(ids(messages.findAll(includePermanent.reprocessSpecification()))).containsExactlyInAnyOrder(exhausted, deadLettered, permanent, legacy);
    }

    @Test
    void filtersNarrowByKindChannelClientAndErrorTextAndTheOldestComeFirst() {
        UUID mine = client();
        UUID other = client();
        UUID oldest = message(mine, MessageStatus.FAILED, FailureKind.EXHAUSTED, "+20", 30);
        UUID newer = message(mine, MessageStatus.FAILED, FailureKind.EXHAUSTED, "+21", 10);
        message(other, MessageStatus.FAILED, FailureKind.EXHAUSTED, "+22", 20);
        jdbc.sql("UPDATE notification_message SET last_error = 'HTTP 503 from provider' WHERE id = :id").param("id", newer).update();
        em.flush();
        em.clear();

        assertThat(ids(messages.findAll(new DeadLetterFilter(mine, null, FailureKind.PERMANENT, null, true).reprocessSpecification()))).isEmpty();
        assertThat(ids(messages.findAll(new DeadLetterFilter(mine, Channel.EMAIL, null, null, true).reprocessSpecification()))).isEmpty();
        assertThat(ids(messages.findAll(new DeadLetterFilter(mine, null, null, "http 503", true).reprocessSpecification()))).containsExactly(newer);

        var ordered = messages.findAll(new DeadLetterFilter(mine, null, null, null, true).reprocessSpecification(),
                PageRequest.of(0, 10, org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "updatedAt")));
        assertThat(ordered.map(NotificationMessage::getId).getContent()).containsExactly(oldest, newer);
    }

    private static Set<UUID> ids(List<NotificationMessage> found) {
        return found.stream().map(NotificationMessage::getId).collect(java.util.stream.Collectors.toSet());
    }
}
