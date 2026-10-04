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

package com.techeazy.notification.config;

import com.techeazy.notification.application.PayloadFingerprints;
import com.techeazy.notification.application.RecipientFingerprints;
import com.techeazy.notification.domain.FieldEncryptor;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.infra.ProviderSecrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

import java.security.SecureRandom;
import java.util.List;

/** Provider-secret, client signing-secret and personal-data encryption with their key rotation (ADR-037), and the startup checks that refuse known development defaults outside {@code local}. */
@Configuration
public class SecretsConfig {

    private static final String DEFAULT_SECRETS_KEY = "development-only-change-me";

    /** The purposes each configured key encrypts for (ADR-037); each is bound to its ciphertexts and must not change. */
    public static final String PROVIDER_SETTINGS = "provider-settings";
    public static final String CLIENT_SIGNING_SECRET = "client-signing-secret";
    public static final String MESSAGE_VARIABLES = "message-variables";

    private static final SecureRandom RANDOM = new SecureRandom();

    @Bean
    @Primary
    FieldEncryptor personalDataEncryptor(Environment env,
                                         @Value("${notification.data-key:" + DEFAULT_SECRETS_KEY + "}") String dataKey,
                                         @Value("${notification.data-key-previous:}") List<String> previousDataKeys) {
        return cipher(env, "notification.data-key", dataKey, previousDataKeys, MESSAGE_VARIABLES);
    }

    /**
     * Recipient and idempotency-payload fingerprints are keyed by {@code notification.fingerprint-key}, which defaults to
     * the data key. It is separate so the data key can be rotated: set it to the old data key first, and existing
     * fingerprints still match.
     */
    @Bean
    RecipientFingerprints recipientFingerprints(Environment env,
                                                @Value("${notification.fingerprint-key:${notification.data-key:"
                                                        + DEFAULT_SECRETS_KEY + "}}") String fingerprintKey) {
        InsecureDefaults.reject(env, "notification.fingerprint-key", fingerprintKey, DEFAULT_SECRETS_KEY);
        InsecureDefaults.requireStrongKey(env, "notification.fingerprint-key", fingerprintKey);
        return new RecipientFingerprints(fingerprintKey);
    }

    @Bean
    PayloadFingerprints payloadFingerprints(Environment env,
                                            @Value("${notification.fingerprint-key:${notification.data-key:"
                                                    + DEFAULT_SECRETS_KEY + "}}") String fingerprintKey) {
        InsecureDefaults.reject(env, "notification.fingerprint-key", fingerprintKey, DEFAULT_SECRETS_KEY);
        InsecureDefaults.requireStrongKey(env, "notification.fingerprint-key", fingerprintKey);
        return new PayloadFingerprints(fingerprintKey);
    }

    @Bean
    ProviderSecrets providerSecrets(Environment env,
                                    @Value("${notification.secrets-key:" + DEFAULT_SECRETS_KEY + "}") String secretsKey,
                                    @Value("${notification.secrets-key-previous:}") List<String> previousSecretsKeys) {
        return new ProviderSecrets(cipher(env, "notification.secrets-key", secretsKey, previousSecretsKeys,
                PROVIDER_SETTINGS));
    }

    @Bean
    ClientSigningSecrets clientSigningSecrets(Environment env,
                                              @Value("${notification.secrets-key:" + DEFAULT_SECRETS_KEY + "}") String secretsKey,
                                              @Value("${notification.secrets-key-previous:}") List<String> previousSecretsKeys) {
        return new ClientSigningSecrets(cipher(env, "notification.secrets-key", secretsKey, previousSecretsKeys,
                CLIENT_SIGNING_SECRET));
    }

    private static AesGcmCipher cipher(Environment env, String property, String key, List<String> previousKeys,
                                       String purpose) {
        InsecureDefaults.reject(env, property, key, DEFAULT_SECRETS_KEY);
        InsecureDefaults.requireStrongKey(env, property, key);
        return new AesGcmCipher(key, previousKeys, purpose, RANDOM);
    }

    /** Exists only so its factory method below runs during startup; nothing depends on the instance itself. */
    record DatabaseCredentialsChecked(String property) {
    }

    @Bean
    DatabaseCredentialsChecked databaseCredentialsChecked(Environment env, @Value("${spring.datasource.password:}") String dbPassword) {
        String property = "spring.datasource.password";
        InsecureDefaults.reject(env, property, dbPassword, "notification", "notification_app");
        return new DatabaseCredentialsChecked(property);
    }
}
