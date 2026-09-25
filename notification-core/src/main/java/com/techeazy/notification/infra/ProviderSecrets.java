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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Encrypts the secret-looking settings of a {@code ProviderConfig} (SMTP password, gateway auth header) at rest
 * with {@link AesGcmCipher}, so a copy of the database alone does not hand over live credentials.
 *
 * <p>Encrypted values are stored with an {@code enc:} prefix; anything without it is treated as plaintext. This
 * means an existing deployment's already-stored plaintext secrets keep working unchanged and are encrypted the
 * next time each provider config is saved, with no forced migration step.
 */
public final class ProviderSecrets {

    private static final String PREFIX = "enc:";

    /** Setting keys treated as secrets, matching what the admin API masks on read. */
    public static final Set<String> SECRET_KEYS = Set.of("password", "authheader", "apikey", "token", "secret");

    private final AesGcmCipher cipher;

    public ProviderSecrets(AesGcmCipher cipher) {
        this.cipher = cipher;
    }

    /** Encrypts secret-looking values that are not already encrypted; everything else passes through unchanged. */
    public Map<String, String> encryptForStorage(Map<String, String> settings) {
        Map<String, String> result = new HashMap<>(settings);
        result.replaceAll((key, value) -> {
            if (value == null || value.isEmpty() || !isSecret(key) || value.startsWith(PREFIX)) {
                return value;
            }
            return PREFIX + cipher.encrypt(value);
        });
        return result;
    }

    /** Decrypts values this class encrypted; a value without the {@code enc:} prefix (legacy plaintext) is returned as-is. */
    public Map<String, String> decryptForUse(Map<String, String> settings) {
        Map<String, String> result = new HashMap<>(settings);
        result.replaceAll((key, value) -> {
            if (value == null || !value.startsWith(PREFIX)) {
                return value;
            }
            return cipher.decrypt(value.substring(PREFIX.length()));
        });
        return result;
    }

    private static boolean isSecret(String key) {
        return SECRET_KEYS.contains(key.toLowerCase());
    }
}
