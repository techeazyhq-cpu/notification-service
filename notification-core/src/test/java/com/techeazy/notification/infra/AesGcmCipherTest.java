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

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Versioned, purpose-bound AES-GCM with key rotation, reading what earlier releases wrote (ADR-037). */
class AesGcmCipherTest {

    private static final String OLD_KEY = "old-key-0123456789abcdef0123456789";
    private static final String NEW_KEY = "new-key-0123456789abcdef0123456789";
    private static final SecureRandom RANDOM = new SecureRandom();

    private static AesGcmCipher cipher(String key, List<String> previous, String purpose) {
        return new AesGcmCipher(key, previous, purpose, RANDOM);
    }

    @Test
    void aValueRoundTripsAndIsWrittenWithTheVersionAndTheKeyId() {
        AesGcmCipher cipher = cipher(NEW_KEY, List.of(), "provider-settings");

        String stored = cipher.encrypt("smtp-password");

        assertThat(stored).startsWith("v2." + cipher.currentKeyId() + ".").doesNotContain("smtp-password");
        assertThat(cipher.decrypt(stored)).isEqualTo("smtp-password");
        assertThat(cipher.needsReencryption(stored)).isFalse();
    }

    @Test
    void aValueEncryptedForOnePurposeCannotBeReadAsAnotherUnderTheSameKey() {
        String providerSecret = cipher(NEW_KEY, List.of(), "provider-settings").encrypt("smtp-password");

        assertThatThrownBy(() -> cipher(NEW_KEY, List.of(), "client-signing-secret").decrypt(providerSecret))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void afterARotationThePreviousKeyStillReadsWhatItWroteAndTheNewKeyWrites() {
        String beforeRotation = cipher(OLD_KEY, List.of(), "provider-settings").encrypt("smtp-password");
        AesGcmCipher rotated = cipher(NEW_KEY, List.of(OLD_KEY), "provider-settings");

        assertThat(rotated.decrypt(beforeRotation)).isEqualTo("smtp-password");
        assertThat(rotated.needsReencryption(beforeRotation)).isTrue();
        String rewritten = rotated.encrypt(rotated.decrypt(beforeRotation));
        assertThat(rotated.needsReencryption(rewritten)).isFalse();
        assertThat(cipher(NEW_KEY, List.of(), "provider-settings").decrypt(rewritten)).isEqualTo("smtp-password");
    }

    @Test
    void aValueUnderAKeyThatIsNoLongerConfiguredNamesTheMissingKey() {
        AesGcmCipher old = cipher(OLD_KEY, List.of(), "provider-settings");
        String stored = old.encrypt("smtp-password");

        assertThatThrownBy(() -> cipher(NEW_KEY, List.of(), "provider-settings").decrypt(stored))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(old.currentKeyId());
    }

    @Test
    void valuesWrittenByEarlierReleasesAreStillReadWithTheCurrentOrAPreviousKey() throws Exception {
        String earlier = earlierFormat(OLD_KEY, "smtp-password");

        assertThat(cipher(OLD_KEY, List.of(), "provider-settings").decrypt(earlier)).isEqualTo("smtp-password");
        assertThat(cipher(NEW_KEY, List.of(OLD_KEY), "client-signing-secret").decrypt(earlier)).isEqualTo("smtp-password");
        assertThat(cipher(OLD_KEY, List.of(), "provider-settings").needsReencryption(earlier)).isTrue();
    }

    @Test
    void aTamperedValueIsRefused() {
        AesGcmCipher cipher = cipher(NEW_KEY, List.of(), "provider-settings");
        String stored = cipher.encrypt("smtp-password");
        char last = stored.charAt(stored.length() - 3);
        String tampered = stored.substring(0, stored.length() - 3) + (last == 'A' ? 'B' : 'A') + stored.substring(stored.length() - 2);

        assertThatThrownBy(() -> cipher.decrypt(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void blankPreviousKeysAreIgnored() {
        AesGcmCipher cipher = cipher(NEW_KEY, List.of("", " "), "provider-settings");

        assertThat(cipher.decrypt(cipher.encrypt("x"))).isEqualTo("x");
    }

    /** Ciphertext exactly as releases before ADR-037 wrote it: SHA-256 of the key, no associated data, bare base64. */
    private static String earlierFormat(String key, String plain) throws Exception {
        byte[] iv = new byte[12];
        RANDOM.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)), "AES"), new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
    }
}
