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

package com.techeazy.notification.application;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** One person gives their address in many spellings; each must find the same messages (ADR-035). */
class RecipientFingerprintsTest {

    private final RecipientFingerprints fingerprints = new RecipientFingerprints("a-data-key");

    @ParameterizedTest(name = "{0} is {1}")
    @CsvSource(delimiter = '|', value = {
            "Ann@Example.COM | ann@example.com",
            "  ann@example.com  | ann@example.com",
            "+1 (415) 555-0123 | +14155550123",
            "+1.415.555.0123 | +14155550123",
            "0044 20 7946 0000 | 00442079460000",
            "Device-Token_AbC | Device-Token_AbC"
    })
    void spellingsOfOneAddressShareAFingerprint(String spelling, String canonical) {
        assertThat(RecipientFingerprints.normalize(spelling)).isEqualTo(canonical);
        assertThat(fingerprints.of(spelling)).isEqualTo(fingerprints.of(canonical));
    }

    @Test
    void aFingerprintIsSixtyFourHexCharactersAndRevealsNothingOfTheAddress() {
        String fingerprint = fingerprints.of("ann@example.com");

        assertThat(fingerprint).matches("[0-9a-f]{64}").doesNotContain("ann");
        assertThat(fingerprints.of("bob@example.com")).isNotEqualTo(fingerprint);
    }

    @Test
    void anotherKeyGivesAnotherFingerprintSoTheyCannotBeMatchedAcrossInstallations() {
        assertThat(new RecipientFingerprints("another-key").of("ann@example.com"))
                .isNotEqualTo(fingerprints.of("ann@example.com"));
    }

    @Test
    void theKeyIsDerivedSoTheFingerprintIsNotAPlainHmacWithTheDataKey() throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec("a-data-key".getBytes(), "HmacSHA256"));
        String plain = java.util.HexFormat.of().formatHex(mac.doFinal("ann@example.com".getBytes()));

        assertThat(fingerprints.of("ann@example.com")).isNotEqualTo(plain);
    }
}
