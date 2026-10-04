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

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Issues clients' request-signing secrets and keeps them encrypted at rest with the credentials key (ADR-036). Unlike
 * an API key, which is stored only as a hash, the service must recompute each signature, so it needs the secret
 * itself; encryption means a copy of the database alone does not hand it over.
 */
public final class ClientSigningSecrets {

    private static final String PREFIX = "nss_";
    private static final int SECRET_BYTES = 32;

    private final AesGcmCipher cipher;
    private final SecureRandom random;

    public ClientSigningSecrets(AesGcmCipher cipher) {
        this(cipher, new SecureRandom());
    }

    ClientSigningSecrets(AesGcmCipher cipher, SecureRandom random) {
        this.cipher = cipher;
        this.random = random;
    }

    /** A new random 256-bit secret, shown to the administrator once and never again. */
    public String generate() {
        byte[] bytes = new byte[SECRET_BYTES];
        random.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    public String encryptForStorage(String secret) {
        return cipher.encrypt(secret);
    }

    public String decryptForUse(String stored) {
        return cipher.decrypt(stored);
    }
}
