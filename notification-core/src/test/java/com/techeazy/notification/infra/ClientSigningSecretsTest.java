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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientSigningSecretsTest {

    private final ClientSigningSecrets secrets = new ClientSigningSecrets(new AesGcmCipher("credentials-key"));

    @Test
    void aSecretIsA256BitRandomValueThatIsNeverIssuedTwice() {
        String first = secrets.generate();

        assertThat(first).matches("nss_[0-9a-f]{64}");
        assertThat(secrets.generate()).isNotEqualTo(first);
    }

    @Test
    void theStoredFormIsEncryptedAndDecryptsBackToTheSecret() {
        String secret = secrets.generate();

        String stored = secrets.encryptForStorage(secret);

        assertThat(stored).doesNotContain(secret.substring(4));
        assertThat(secrets.decryptForUse(stored)).isEqualTo(secret);
    }

    @Test
    void aDifferentCredentialsKeyCannotReadTheStoredSecret() {
        String stored = secrets.encryptForStorage(secrets.generate());
        ClientSigningSecrets otherKey = new ClientSigningSecrets(new AesGcmCipher("another-key"));

        assertThatThrownBy(() -> otherKey.decryptForUse(stored)).isInstanceOf(IllegalStateException.class);
    }
}
