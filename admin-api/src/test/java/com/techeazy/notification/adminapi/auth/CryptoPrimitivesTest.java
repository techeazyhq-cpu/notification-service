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

package com.techeazy.notification.adminapi.auth;

import org.junit.jupiter.api.Test;

import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CryptoPrimitivesTest {

    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private final Totp totp = new Totp(new SecureRandom());

    @Test
    void base32RoundTripsAndMatchesTheRfcAlphabet() {
        byte[] data = "12345678901234567890".getBytes();

        assertThat(Base32.encode(data)).isEqualTo(RFC_SECRET);
        assertThat(Base32.decode(RFC_SECRET)).isEqualTo(data);
        assertThat(Base32.decode(RFC_SECRET.toLowerCase())).isEqualTo(data);
    }

    @Test
    void base32RejectsCharactersOutsideTheAlphabet() {
        assertThatThrownBy(() -> Base32.decode("ABC1")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void totpMatchesTheRfc6238TestVectorsTruncatedToSixDigits() {
        assertThat(totp.codeAt(RFC_SECRET, 59 / 30)).isEqualTo("287082");
        assertThat(totp.codeAt(RFC_SECRET, 1111111109L / 30)).isEqualTo("081804");
        assertThat(totp.codeAt(RFC_SECRET, 1234567890L / 30)).isEqualTo("005924");
        assertThat(totp.codeAt(RFC_SECRET, 2000000000L / 30)).isEqualTo("279037");
    }

    @Test
    void aCodeIsAcceptedInsideTheDriftWindowButNotOutsideIt() {
        long now = 1_700_000_000_000L;
        long step = totp.stepAt(now);

        assertThat(totp.verify(RFC_SECRET, totp.codeAt(RFC_SECRET, step), now, 0)).hasValue(step);
        assertThat(totp.verify(RFC_SECRET, totp.codeAt(RFC_SECRET, step - 1), now, 0)).hasValue(step - 1);
        assertThat(totp.verify(RFC_SECRET, totp.codeAt(RFC_SECRET, step + 1), now, 0)).hasValue(step + 1);
        assertThat(totp.verify(RFC_SECRET, totp.codeAt(RFC_SECRET, step - 2), now, 0)).isEmpty();
    }

    @Test
    void aCodeCannotBeReplayedOnceItsStepWasAccepted() {
        long now = 1_700_000_000_000L;
        long step = totp.stepAt(now);
        String code = totp.codeAt(RFC_SECRET, step);

        assertThat(totp.verify(RFC_SECRET, code, now, step)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, code, now, step - 1)).hasValue(step);
    }

    @Test
    void malformedCodesAreRejected() {
        assertThat(totp.verify(RFC_SECRET, null, 0, 0)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, "12345", 0, 0)).isEmpty();
        assertThat(totp.verify(RFC_SECRET, "abcdef", 0, 0)).isEmpty();
    }

    @Test
    void theProvisioningUriCarriesTheSecretAndEncodesTheLabel() {
        String uri = totp.provisioningUri("Notification Admin", "ops@example.com", RFC_SECRET);

        assertThat(uri).startsWith("otpauth://totp/Notification%20Admin:ops%40example.com?secret=" + RFC_SECRET)
                .contains("issuer=Notification%20Admin").contains("digits=6").contains("period=30");
    }

    @Test
    void generatedSecretsAreDistinctBase32() {
        assertThat(totp.newSecret()).matches("[A-Z2-7]{32}").isNotEqualTo(totp.newSecret());
    }

    @Test
    void secretsAreEncryptedAtRestAndOnlyReadableWithTheSameKey() {
        SecretCipher cipher = new SecretCipher("key-one", new SecureRandom());

        String stored = cipher.encrypt(RFC_SECRET);

        assertThat(stored).doesNotContain(RFC_SECRET);
        assertThat(cipher.decrypt(stored)).isEqualTo(RFC_SECRET);
        assertThat(cipher.encrypt(RFC_SECRET)).isNotEqualTo(stored);
        SecretCipher otherCipher = new SecretCipher("key-two", new SecureRandom());
        assertThatThrownBy(() -> otherCipher.decrypt(stored)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void passwordPolicyRequiresLengthAndRejectsTheUserNameAndRepetition() {
        assertThat(PasswordPolicy.violation("correct horse battery", "admin")).isNull();
        assertThat(PasswordPolicy.violation("short", "admin")).contains("at least 12");
        assertThat(PasswordPolicy.violation("x".repeat(129), "admin")).contains("at most 128");
        assertThat(PasswordPolicy.violation("my-ADMIN-password-1", "admin")).contains("user name");
        assertThat(PasswordPolicy.violation("aaaaaaaaaaaaaaaa", "root")).contains("repetitive");
        assertThat(PasswordPolicy.violation(null, "admin")).isNotNull();
    }
}
