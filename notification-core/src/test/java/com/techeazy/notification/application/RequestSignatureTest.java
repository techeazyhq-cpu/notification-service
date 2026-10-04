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

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signature format is a public contract that clients implement in their own languages, so the expected values
 * here were computed independently (Python's hmac and hashlib), not by the code under test. The client guide
 * publishes the first vector.
 */
class RequestSignatureTest {

    private static final String SECRET = "nss_test";
    private static final String TIMESTAMP = "1767225600";
    private static final String NONCE = "0123456789abcdef";
    private static final byte[] BODY = "{\"channel\":\"SMS\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void theSignedTextIsTheSevenDocumentedLines() {
        String canonical = RequestSignature.canonical(TIMESTAMP, NONCE, "post", "/v1/notifications", null, BODY);

        assertThat(canonical).isEqualTo("""
                NS1-HMAC-SHA256
                1767225600
                0123456789abcdef
                POST
                /v1/notifications

                7ebfa9c725879072c4da140b83149548e8bbf692760087cbfe3237c4359fb1ea""");
    }

    @Test
    void aBodyRequestSignsToTheIndependentlyComputedValue() {
        String canonical = RequestSignature.canonical(TIMESTAMP, NONCE, "POST", "/v1/notifications", "", BODY);

        assertThat(RequestSignature.sign(SECRET, canonical))
                .isEqualTo("2295ae2e9e31c56816963cbcc04632396fa6471d25a8cb667f9003711e053503");
    }

    @Test
    void aRequestWithoutABodyHashesNoBytesAndSignsItsQuery() {
        String canonical = RequestSignature.canonical(TIMESTAMP, NONCE, "GET", "/v1/notifications",
                "status=SENT&page=2", null);

        assertThat(canonical).endsWith("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(RequestSignature.sign(SECRET, canonical))
                .isEqualTo("32ec965cf932dc40b326dbfd2e2bd479de8e96a91fdfcac571378f7ebcfc00dd");
    }

    @Test
    void onlyTheExactSignatureOfTheExactTextMatches() {
        String canonical = RequestSignature.canonical(TIMESTAMP, NONCE, "POST", "/v1/notifications", "", BODY);
        String signature = RequestSignature.sign(SECRET, canonical);
        String otherBody = RequestSignature.canonical(TIMESTAMP, NONCE, "POST", "/v1/notifications", "",
                "{\"channel\":\"EMAIL\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(RequestSignature.matches(SECRET, canonical, signature)).isTrue();
        assertThat(RequestSignature.matches(SECRET, otherBody, signature)).isFalse();
        assertThat(RequestSignature.matches("nss_other", canonical, signature)).isFalse();
        assertThat(RequestSignature.matches(SECRET, canonical, signature.toUpperCase())).isFalse();
        assertThat(RequestSignature.matches(SECRET, canonical, "v1=" + signature)).isFalse();
        assertThat(RequestSignature.matches(SECRET, canonical, null)).isFalse();
    }

    @Test
    void noncesAndTimestampsMustBeWellFormed() {
        assertThat(RequestSignature.isWellFormedNonce("0123456789abcdef")).isTrue();
        assertThat(RequestSignature.isWellFormedNonce("8f14e45f-ceea-467a-9575-1d0d7e8e3c11")).isTrue();
        assertThat(RequestSignature.isWellFormedNonce("too-short")).isFalse();
        assertThat(RequestSignature.isWellFormedNonce("has space in it 1234")).isFalse();
        assertThat(RequestSignature.isWellFormedNonce("x".repeat(65))).isFalse();
        assertThat(RequestSignature.isWellFormedNonce(null)).isFalse();

        assertThat(RequestSignature.isWellFormedTimestamp(TIMESTAMP)).isTrue();
        assertThat(RequestSignature.isWellFormedTimestamp("-1")).isFalse();
        assertThat(RequestSignature.isWellFormedTimestamp("2026-01-01T00:00:00Z")).isFalse();
        assertThat(RequestSignature.isWellFormedTimestamp(null)).isFalse();
    }
}
