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

import com.techeazy.notification.domain.FieldEncryptor;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ProviderSecrets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;

/** Provider-secret encryption, and the startup checks that refuse known development defaults outside {@code local}. */
@Configuration
public class SecretsConfig {

    private static final String DEFAULT_SECRETS_KEY = "development-only-change-me";

    @Bean
    AesGcmCipher providerSecretsCipher(Environment env, @Value("${notification.secrets-key:" + DEFAULT_SECRETS_KEY + "}") String secretsKey) {
        InsecureDefaults.reject(env, "notification.secrets-key", secretsKey, DEFAULT_SECRETS_KEY);
        return new AesGcmCipher(secretsKey);
    }

    @Bean
    @Primary
    FieldEncryptor personalDataEncryptor(Environment env, @Value("${notification.data-key:" + DEFAULT_SECRETS_KEY + "}") String dataKey) {
        InsecureDefaults.reject(env, "notification.data-key", dataKey, DEFAULT_SECRETS_KEY);
        return new AesGcmCipher(dataKey);
    }

    @Bean
    ProviderSecrets providerSecrets(AesGcmCipher providerSecretsCipher) {
        return new ProviderSecrets(providerSecretsCipher);
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
