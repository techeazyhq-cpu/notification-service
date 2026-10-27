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

package com.techeazy.notification.drafting;

import com.techeazy.notification.config.SecretsConfig;
import com.techeazy.notification.drafting.DraftingSettingsView.ApiKeySource;
import com.techeazy.notification.infra.AesGcmCipher;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The settings an administrator saves in the console, against a real PostgreSQL migrated by the real changelog;
 * skipped automatically without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class DraftingSettingsStoreTest {

    private static final String SECRETS_KEY = "drafting-settings-test-key-0123456789abcdef";
    private static final String ENVIRONMENT_KEY = "sk-ant-from-environment-1111";
    private static final String CONSOLE_KEY = "sk-ant-from-console-2222";
    private static final Instant NOW = Instant.parse("2026-10-21T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;

    private final AesGcmCipher cipher = new AesGcmCipher(SECRETS_KEY, List.of(),
            SecretsConfig.TEMPLATE_DRAFTING_API_KEY, new SecureRandom());
    private final DraftingProperties environment = new DraftingProperties();
    private final MovableClock clock = new MovableClock(NOW);
    private DraftingSettingsStore store;

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

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM template_drafting_settings").update();
        environment.setApiKey(ENVIRONMENT_KEY);
        store = new DraftingSettingsStore(jdbc, cipher, environment, clock);
    }

    private static DraftingSettingsChange change(String apiKey, boolean removeApiKey) {
        return new DraftingSettingsChange(true, LlmProvider.ANTHROPIC, null, apiKey, removeApiKey,
                "claude-sonnet-5-5", "medium", 4000, 1_500_000, 50_000, 3);
    }

    @Test
    void withNothingSavedTheEnvironmentDefaultsApply() {
        DraftingSettingsView view = store.describe();

        assertThat(store.current().apiKey()).isEqualTo(ENVIRONMENT_KEY);
        assertThat(store.current().model()).isEqualTo("claude-opus-5-5");
        assertThat(view.enabled()).isTrue();
        assertThat(view.apiKeySource()).isEqualTo(ApiKeySource.ENVIRONMENT);
        assertThat(view.apiKeyHint()).isEqualTo("…1111");
        assertThat(view.updatedBy()).isNull();
    }

    @Test
    void withNoKeyAnywhereDraftingIsOff() {
        environment.setApiKey("");

        assertThat(store.current().active()).isFalse();
        assertThat(store.describe().apiKeySource()).isEqualTo(ApiKeySource.NONE);
        assertThat(store.describe().enabled()).isFalse();
    }

    @Test
    void aSavedKeyIsStoredEncryptedAndUsedForTheNextDraft() {
        DraftingSettingsView saved = store.save(change(CONSOLE_KEY, false), "alice");

        String stored = jdbc.sql("SELECT api_key FROM template_drafting_settings").query(String.class).single();
        assertThat(stored).doesNotContain(CONSOLE_KEY);
        assertThat(cipher.decrypt(stored)).isEqualTo(CONSOLE_KEY);
        assertThat(store.current()).isEqualTo(new DraftingSettings(true, LlmProvider.ANTHROPIC, null, CONSOLE_KEY,
                "claude-sonnet-5-5", "medium", 4000, 1_500_000, 50_000, 3));
        assertThat(saved.apiKeySource()).isEqualTo(ApiKeySource.STORED);
        assertThat(saved.apiKeyHint()).isEqualTo("…2222");
        assertThat(saved.updatedBy()).isEqualTo("alice");
        assertThat(saved.updatedAt()).isEqualTo(NOW);
    }

    @Test
    void savingWithoutAKeyKeepsTheStoredOne() {
        store.save(change(CONSOLE_KEY, false), "alice");

        store.save(change(null, false), "bob");

        assertThat(store.current().apiKey()).isEqualTo(CONSOLE_KEY);
        assertThat(store.describe().updatedBy()).isEqualTo("bob");
    }

    @Test
    void removingTheStoredKeyFallsBackToTheEnvironmentKey() {
        store.save(change(CONSOLE_KEY, false), "alice");

        DraftingSettingsView view = store.save(change(null, true), "alice");

        assertThat(store.current().apiKey()).isEqualTo(ENVIRONMENT_KEY);
        assertThat(view.apiKeySource()).isEqualTo(ApiKeySource.ENVIRONMENT);
    }

    @Test
    void switchedOffInTheConsoleDraftingIsOffDespiteTheEnvironmentKey() {
        store.save(new DraftingSettingsChange(false, LlmProvider.ANTHROPIC, null, null, false, "claude-opus-5-5",
                "low", 8000, 1, 1, 5), "alice");

        assertThat(store.current().active()).isFalse();
        assertThat(store.describe().enabled()).isFalse();
    }

    @Test
    void anotherProviderAndEndpointAreSavedAndNeedNoKey() {
        environment.setApiKey("");

        DraftingSettingsView saved = store.save(new DraftingSettingsChange(true, LlmProvider.OLLAMA,
                "http://host.docker.internal:11434/v1", null, false, "llama3.1:8b", "low", 2000, 0, 0, 10), "alice");

        assertThat(store.current().provider()).isEqualTo(LlmProvider.OLLAMA);
        assertThat(store.current().endpoint()).isEqualTo("http://host.docker.internal:11434/v1");
        assertThat(store.current().active()).isTrue();
        assertThat(saved.provider()).isEqualTo(LlmProvider.OLLAMA);
        assertThat(saved.baseUrl()).isEqualTo("http://host.docker.internal:11434/v1");
        assertThat(saved.enabled()).isTrue();
        assertThat(saved.apiKeySource()).isEqualTo(ApiKeySource.NONE);
    }

    @Test
    void anotherInstanceSeesTheChangeOnceItsCacheExpires() {
        DraftingSettingsStore otherInstance = new DraftingSettingsStore(jdbc, cipher, environment, clock);
        assertThat(otherInstance.current().model()).isEqualTo("claude-opus-5-5");

        store.save(change(CONSOLE_KEY, false), "alice");

        assertThat(otherInstance.current().model()).isEqualTo("claude-opus-5-5");
        clock.advance(environment.getSettingsCacheTtl().plusSeconds(1));
        assertThat(otherInstance.current().model()).isEqualTo("claude-sonnet-5-5");
    }

    @Test
    void theInstanceThatSavesUsesTheChangeAtOnce() {
        assertThat(store.current().model()).isEqualTo("claude-opus-5-5");

        store.save(change(CONSOLE_KEY, false), "alice");

        assertThat(store.current().model()).isEqualTo("claude-sonnet-5-5");
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
