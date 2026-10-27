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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techeazy.notification.adminapi.auth.AuthConfiguration;
import com.techeazy.notification.config.SecretsConfig;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.infra.ProviderSecrets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * Rewrites the stored secrets that are still in the earlier format or under a previous key with the current key
 * (ADR-037): client signing secrets, provider settings, administrators' two-factor secrets and the AI drafting API key
 * (ADR-044). It runs when the admin
 * API starts and then every hour, so after a key rotation the previous key can be removed from configuration once a
 * run reports nothing left. Each row is replaced only if it still holds what was read, so concurrent runs on several
 * instances, or an administrator's edit in between, never lose a change.
 *
 * <p>Message variables are not rewritten: they are erased after the personal-data retention period, so a previous
 * data key only has to stay configured for that long.
 */
@Component
class SecretReencryption {

    private static final Logger LOG = LoggerFactory.getLogger(SecretReencryption.class);
    private static final TypeReference<Map<String, String>> SETTINGS = new TypeReference<>() { };

    /** How many rows a run rewrote, per kind of secret. */
    record Report(int signingSecrets, int providerSettings, int twoFactorSecrets, int draftingApiKeys) {
        int total() {
            return signingSecrets + providerSettings + twoFactorSecrets + draftingApiKeys;
        }
    }

    private final JdbcClient jdbc;
    private final ClientSigningSecrets signingSecrets;
    private final ProviderSecrets providerSecrets;
    private final AesGcmCipher twoFactorCipher;
    private final AesGcmCipher draftingApiKeyCipher;
    private final ObjectMapper json = new ObjectMapper();

    SecretReencryption(JdbcClient jdbc, ClientSigningSecrets signingSecrets, ProviderSecrets providerSecrets,
                       @Qualifier(AuthConfiguration.TWO_FACTOR_CIPHER) AesGcmCipher twoFactorCipher,
                       @Qualifier(SecretsConfig.TEMPLATE_DRAFTING_API_KEY_CIPHER) AesGcmCipher draftingApiKeyCipher) {
        this.jdbc = jdbc;
        this.signingSecrets = signingSecrets;
        this.providerSecrets = providerSecrets;
        this.twoFactorCipher = twoFactorCipher;
        this.draftingApiKeyCipher = draftingApiKeyCipher;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onStart() {
        runSafely();
    }

    @Scheduled(initialDelayString = "${admin.reencryption-interval-ms:3600000}",
            fixedDelayString = "${admin.reencryption-interval-ms:3600000}")
    void onSchedule() {
        runSafely();
    }

    Report run() {
        Report report = new Report(signingSecrets(), providerSettings(), twoFactorSecrets(), draftingApiKeys());
        if (report.total() > 0) {
            LOG.info("Re-encrypted under the current keys: {} client signing secret(s), {} provider setting(s), "
                    + "{} two-factor secret(s), {} drafting API key(s)", report.signingSecrets(),
                    report.providerSettings(), report.twoFactorSecrets(), report.draftingApiKeys());
        }
        return report;
    }

    private void runSafely() {
        try {
            run();
        } catch (RuntimeException e) {
            LOG.error("Re-encrypting stored secrets failed; it is retried on the next run", e);
        }
    }

    /** Both the current signing secrets and those kept for a rotation's grace period (ADR-051). */
    private int signingSecrets() {
        return signingSecrets("signing_secret") + signingSecrets("previous_signing_secret");
    }

    private int signingSecrets(String columnName) {
        int rewritten = 0;
        for (Map.Entry<UUID, String> row : column("SELECT id, " + columnName + " AS value FROM client WHERE "
                + columnName + " IS NOT NULL")) {
            if (signingSecrets.needsReencryption(row.getValue())) {
                rewritten += jdbc.sql("UPDATE client SET " + columnName + " = :new WHERE id = :id AND " + columnName
                                + " = :old")
                        .param("new", signingSecrets.reencrypt(row.getValue())).param("id", row.getKey())
                        .param("old", row.getValue()).update();
            }
        }
        return rewritten;
    }

    private int twoFactorSecrets() {
        int rewritten = 0;
        for (Map.Entry<UUID, String> row : column("SELECT id, totp_secret AS value FROM admin_user WHERE totp_secret IS NOT NULL")) {
            if (twoFactorCipher.needsReencryption(row.getValue())) {
                rewritten += jdbc.sql("UPDATE admin_user SET totp_secret = :new WHERE id = :id AND totp_secret = :old")
                        .param("new", twoFactorCipher.encrypt(twoFactorCipher.decrypt(row.getValue())))
                        .param("id", row.getKey()).param("old", row.getValue()).update();
            }
        }
        return rewritten;
    }

    private int draftingApiKeys() {
        int rewritten = 0;
        for (String stored : jdbc.sql("SELECT api_key FROM template_drafting_settings WHERE api_key IS NOT NULL")
                .query(String.class).list()) {
            if (draftingApiKeyCipher.needsReencryption(stored)) {
                rewritten += jdbc.sql("UPDATE template_drafting_settings SET api_key = :new WHERE api_key = :old")
                        .param("new", draftingApiKeyCipher.encrypt(draftingApiKeyCipher.decrypt(stored)))
                        .param("old", stored).update();
            }
        }
        return rewritten;
    }

    private int providerSettings() {
        int rewritten = 0;
        for (Map.Entry<UUID, String> row : column("SELECT id, settings::text AS value FROM provider_config")) {
            Map<String, String> settings = read(row.getValue());
            if (providerSecrets.needsReencryption(settings)) {
                rewritten += jdbc.sql("UPDATE provider_config SET settings = CAST(:new AS jsonb) "
                                + "WHERE id = :id AND settings = CAST(:old AS jsonb)")
                        .param("new", write(providerSecrets.reencrypt(settings))).param("id", row.getKey())
                        .param("old", row.getValue()).update();
            }
        }
        return rewritten;
    }

    private java.util.List<Map.Entry<UUID, String>> column(String sql) {
        return jdbc.sql(sql).query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("value"))).list();
    }

    private Map<String, String> read(String settings) {
        try {
            return json.readValue(settings, SETTINGS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Provider settings are not a JSON object of strings", e);
        }
    }

    private String write(Map<String, String> settings) {
        try {
            return json.writeValueAsString(settings);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write provider settings", e);
        }
    }
}
