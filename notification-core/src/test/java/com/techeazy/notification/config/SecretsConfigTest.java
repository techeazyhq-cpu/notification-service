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

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecretsConfigTest {

    private static final String DEFAULT_KEY = "development-only-change-me";
    private static final String STRONG_KEY = "0123456789abcdef0123456789abcdef";

    private final SecretsConfig config = new SecretsConfig();

    private static MockEnvironment local() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("local");
        return env;
    }

    @Test
    void refusesTheShippedProviderSecretsKeyOutsideLocal() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> config.providerSecrets(env, DEFAULT_KEY, List.of())).hasMessageContaining("notification.secrets-key");
        assertThatThrownBy(() -> config.clientSigningSecrets(env, DEFAULT_KEY, List.of())).hasMessageContaining("notification.secrets-key");
    }

    @Test
    void refusesTheShippedPersonalDataKeyOutsideLocal() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> config.personalDataEncryptor(env, DEFAULT_KEY, List.of())).hasMessageContaining("notification.data-key");
    }

    @Test
    void refusesAnEncryptionKeyTooShortToBeRandomOutsideLocal() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> config.providerSecrets(env, "short but not the default", List.of()))
                .hasMessageContaining("notification.secrets-key").hasMessageContaining("at least 32");
        assertThatThrownBy(() -> config.personalDataEncryptor(env, "short but not the default", List.of()))
                .hasMessageContaining("notification.data-key");
        assertThatThrownBy(() -> config.recipientFingerprints(env, "short but not the default"))
                .hasMessageContaining("notification.fingerprint-key");
        assertThat(config.providerSecrets(local(), "short", List.of())).isNotNull();
    }

    @Test
    void refusesShippedDatabasePasswordsOutsideLocal() {
        MockEnvironment env = new MockEnvironment();

        assertThatThrownBy(() -> config.databaseCredentialsChecked(env, "notification")).hasMessageContaining("spring.datasource.password");
        assertThatThrownBy(() -> config.databaseCredentialsChecked(env, "notification_app")).hasMessageContaining("spring.datasource.password");
    }

    @Test
    void acceptsTheDefaultsInLocalAndRealValuesAnywhere() {
        assertThat(config.databaseCredentialsChecked(local(), "notification").property()).isEqualTo("spring.datasource.password");
        assertThat(config.databaseCredentialsChecked(new MockEnvironment(), "a real password").property()).isNotBlank();
        assertThat(config.personalDataEncryptor(local(), DEFAULT_KEY, List.of()).encrypt("x")).isNotBlank();
        assertThat(config.providerSecrets(new MockEnvironment(), STRONG_KEY, List.of())).isNotNull();
    }
}
