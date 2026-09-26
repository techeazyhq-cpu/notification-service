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

package com.techeazy.notification.infra;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderSecretsTest {

    private final ProviderSecrets secrets = new ProviderSecrets(new AesGcmCipher("test-key"));

    @Test
    void encryptsSecretKeysAndLeavesOthersAlone() {
        Map<String, String> stored = secrets.encryptForStorage(Map.of(
                "host", "smtp.example.com", "password", "hunter2", "authHeader", "Bearer abc"));

        assertThat(stored).containsEntry("host", "smtp.example.com");
        assertThat(stored.get("password")).startsWith("enc:").isNotEqualTo("enc:hunter2");
        assertThat(stored.get("authHeader")).startsWith("enc:");
    }

    @Test
    void roundTripsThroughStorageAndBackToTheOriginalValue() {
        Map<String, String> stored = secrets.encryptForStorage(Map.of("password", "hunter2"));
        Map<String, String> used = secrets.decryptForUse(stored);

        assertThat(used).containsEntry("password", "hunter2");
    }

    @Test
    void leavesLegacyPlaintextUntouchedOnDecrypt() {
        Map<String, String> legacy = Map.of("password", "still-plaintext-from-before-this-change");

        assertThat(secrets.decryptForUse(legacy)).containsEntry("password", "still-plaintext-from-before-this-change");
    }

    @Test
    void doesNotDoubleEncryptAnAlreadyEncryptedValue() {
        Map<String, String> stored = secrets.encryptForStorage(Map.of("password", "hunter2"));
        Map<String, String> storedAgain = secrets.encryptForStorage(stored);

        assertThat(storedAgain).containsEntry("password", stored.get("password"));
    }

    @Test
    void leavesBlankOrMissingSecretsAlone() {
        Map<String, String> stored = secrets.encryptForStorage(Map.of("password", ""));

        assertThat(stored.get("password")).isEmpty();
    }
}
