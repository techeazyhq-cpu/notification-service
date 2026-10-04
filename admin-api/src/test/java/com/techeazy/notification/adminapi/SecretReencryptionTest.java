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

import com.techeazy.notification.adminapi.auth.AuthConfiguration;
import com.techeazy.notification.config.SecretsConfig;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.infra.ProviderSecrets;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** After a key rotation, every stored secret ends up under the new key and still reads the same (ADR-037). */
@Testcontainers(disabledWithoutDocker = true)
class SecretReencryptionTest {

    private static final String OLD_SECRETS_KEY = "old-secrets-key-0123456789abcdef0123";
    private static final String NEW_SECRETS_KEY = "new-secrets-key-0123456789abcdef0123";
    private static final String OLD_TWO_FACTOR_KEY = "old-two-factor-key-0123456789abcdef";
    private static final String NEW_TWO_FACTOR_KEY = "new-two-factor-key-0123456789abcdef";
    private static final SecureRandom RANDOM = new SecureRandom();

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static JdbcClient jdbc;

    private final ClientSigningSecrets oldSigning = new ClientSigningSecrets(
            new AesGcmCipher(OLD_SECRETS_KEY, List.of(), SecretsConfig.CLIENT_SIGNING_SECRET, RANDOM));
    private final ProviderSecrets oldProviders = new ProviderSecrets(
            new AesGcmCipher(OLD_SECRETS_KEY, List.of(), SecretsConfig.PROVIDER_SETTINGS, RANDOM));
    private final AesGcmCipher oldTwoFactor = new AesGcmCipher(OLD_TWO_FACTOR_KEY, List.of(),
            AuthConfiguration.TWO_FACTOR_SECRET, RANDOM);

    private final ClientSigningSecrets newSigning = new ClientSigningSecrets(
            new AesGcmCipher(NEW_SECRETS_KEY, List.of(OLD_SECRETS_KEY), SecretsConfig.CLIENT_SIGNING_SECRET, RANDOM));
    private final ProviderSecrets newProviders = new ProviderSecrets(
            new AesGcmCipher(NEW_SECRETS_KEY, List.of(OLD_SECRETS_KEY), SecretsConfig.PROVIDER_SETTINGS, RANDOM));
    private final AesGcmCipher newTwoFactor = new AesGcmCipher(NEW_TWO_FACTOR_KEY, List.of(OLD_TWO_FACTOR_KEY),
            AuthConfiguration.TWO_FACTOR_SECRET, RANDOM);

    private SecretReencryption reencryption;

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
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM client").update();
        jdbc.sql("DELETE FROM provider_config").update();
        jdbc.sql("DELETE FROM admin_user").update();
        reencryption = new SecretReencryption(jdbc, newSigning, newProviders, newTwoFactor);
    }

    @Test
    void everySecretWrittenUnderThePreviousKeysIsRewrittenUnderTheCurrentOnesAndReadsTheSame() {
        UUID client = client(oldSigning.encryptForStorage("nss_signing"));
        UUID provider = provider(oldProviders.encryptForStorage(Map.of("host", "smtp.example.com", "password", "pw")));
        UUID admin = admin(oldTwoFactor.encrypt("JBSWY3DPEHPK3PXP"));

        SecretReencryption.Report report = reencryption.run();

        assertThat(report).isEqualTo(new SecretReencryption.Report(1, 1, 1));
        String signing = jdbc.sql("SELECT signing_secret FROM client WHERE id = :id").param("id", client).query(String.class).single();
        assertThat(newSigning.needsReencryption(signing)).isFalse();
        assertThat(onlyCurrentKey(SecretsConfig.CLIENT_SIGNING_SECRET).decryptForUse(signing)).isEqualTo("nss_signing");
        Map<String, String> settings = newProviders.decryptForUse(providerSettings(provider));
        assertThat(settings).containsEntry("password", "pw").containsEntry("host", "smtp.example.com");
        assertThat(newProviders.needsReencryption(providerSettings(provider))).isFalse();
        String totp = jdbc.sql("SELECT totp_secret FROM admin_user WHERE id = :id").param("id", admin).query(String.class).single();
        assertThat(new AesGcmCipher(NEW_TWO_FACTOR_KEY, List.of(), AuthConfiguration.TWO_FACTOR_SECRET, RANDOM).decrypt(totp))
                .isEqualTo("JBSWY3DPEHPK3PXP");
    }

    @Test
    void aSecondRunFindsNothingLeftSoThePreviousKeysCanBeRemoved() {
        client(oldSigning.encryptForStorage("nss_signing"));
        admin(oldTwoFactor.encrypt("JBSWY3DPEHPK3PXP"));
        reencryption.run();

        assertThat(reencryption.run()).isEqualTo(new SecretReencryption.Report(0, 0, 0));
    }

    @Test
    void aProviderPasswordStillStoredInPlaintextGetsEncrypted() {
        UUID provider = provider(Map.of("host", "smtp.example.com", "password", "plain-pw"));

        reencryption.run();

        assertThat(providerSettings(provider).get("password")).startsWith("enc:v2.");
        assertThat(newProviders.decryptForUse(providerSettings(provider))).containsEntry("password", "plain-pw");
    }

    private ClientSigningSecrets onlyCurrentKey(String purpose) {
        return new ClientSigningSecrets(new AesGcmCipher(NEW_SECRETS_KEY, List.of(), purpose, RANDOM));
    }

    private static UUID client(String signingSecret) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, signing_secret, "
                        + "created_at, updated_at) VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'SMS', :secret, :now, :now)")
                .param("id", id).param("name", "c-" + id).param("hash", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .param("secret", signingSecret).param("now", Timestamp.from(Instant.now())).update();
        return id;
    }

    private static UUID provider(Map<String, String> settings) {
        UUID id = UUID.randomUUID();
        StringBuilder json = new StringBuilder("{");
        settings.forEach((k, v) -> json.append(json.length() > 1 ? "," : "").append('"').append(k).append("\":\"").append(v).append('"'));
        json.append('}');
        jdbc.sql("INSERT INTO provider_config (id, channel, name, type, settings, enabled, created_at, updated_at) "
                        + "VALUES (:id, 'EMAIL', :name, 'SMTP', CAST(:settings AS jsonb), true, now(), now())")
                .param("id", id).param("name", "p-" + id).param("settings", json.toString()).update();
        return id;
    }

    private static UUID admin(String totpSecret) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO admin_user (id, username, password_hash, totp_secret, totp_enabled, role, created_at) "
                        + "VALUES (:id, :name, '{bcrypt}x', :secret, true, 'ADMIN', now())")
                .param("id", id).param("name", "a-" + id).param("secret", totpSecret).update();
        return id;
    }

    private static Map<String, String> providerSettings(UUID id) {
        String json = jdbc.sql("SELECT settings::text FROM provider_config WHERE id = :id").param("id", id).query(String.class).single();
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() { });
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
