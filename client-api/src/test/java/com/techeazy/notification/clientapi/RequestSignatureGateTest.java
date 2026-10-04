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
package com.techeazy.notification.clientapi;

import com.techeazy.notification.application.RequestSignature;
import com.techeazy.notification.clientapi.RequestSignatureGate.Admission;
import com.techeazy.notification.clientapi.RequestSignatureGate.SigningPolicy;
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.infra.AesGcmCipher;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.port.NonceStore;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** How the client API admits or refuses requests by their signature (ADR-036). */
class RequestSignatureGateTest {

    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");
    private static final Duration WINDOW = Duration.ofMinutes(5);
    private static final UUID CLIENT = UUID.randomUUID();
    private static final String SECRET = "nss_" + "ab".repeat(32);
    private static final String BODY = "{\"channel\":\"SMS\",\"recipient\":\"+15550100\",\"body\":\"hi\"}";

    private final ClientSigningSecrets secrets = new ClientSigningSecrets(new AesGcmCipher("credentials-key"));
    private final InMemoryNonces nonces = new InMemoryNonces();
    private final RequestSignatureGate gate = new RequestSignatureGate(nonces, secrets,
            Clock.fixed(NOW, ZoneOffset.UTC), WINDOW, 1024);
    private final SigningPolicy optional = new SigningPolicy(secrets.encryptForStorage(SECRET), false);
    private final SigningPolicy required = new SigningPolicy(secrets.encryptForStorage(SECRET), true);

    @Test
    void anUnsignedRequestPassesWhenTheClientDoesNotRequireSignatures() throws IOException {
        MockHttpServletRequest request = post(BODY);

        Admission admission = gate.admit(request, CLIENT, SigningPolicy.NONE);

        assertThat(admission.admitted()).isTrue();
        assertThat(admission.request()).isSameAs(request);
    }

    @Test
    void aClientThatRequiresSignaturesRefusesUnsignedWritesButNotReads() throws IOException {
        Admission write = gate.admit(post(BODY), CLIENT, required);
        Admission read = gate.admit(new MockHttpServletRequest("GET", "/v1/notifications"), CLIENT, required);

        assertThat(write.refusal()).isEqualTo(ErrorCode.SIGNATURE_REQUIRED);
        assertThat(read.admitted()).isTrue();
    }

    @Test
    void aCorrectlySignedRequestIsAdmittedAndItsBodyCanStillBeRead() throws IOException {
        MockHttpServletRequest request = signed(post(BODY), NOW, "nonce-accepted-0001", BODY);

        Admission admission = gate.admit(request, CLIENT, required);

        assertThat(admission.admitted()).isTrue();
        assertThat(StreamUtils.copyToString(admission.request().getInputStream(), StandardCharsets.UTF_8)).isEqualTo(BODY);
        assertThat(nonces.used).containsKey(CLIENT + "/nonce-accepted-0001");
    }

    @Test
    void theSameSignedRequestSentAgainIsRefusedAsAReplay() throws IOException {
        gate.admit(signed(post(BODY), NOW, "nonce-replayed-001", BODY), CLIENT, optional);

        Admission again = gate.admit(signed(post(BODY), NOW, "nonce-replayed-001", BODY), CLIENT, optional);

        assertThat(again.refusal()).isEqualTo(ErrorCode.REQUEST_REPLAYED);
    }

    @Test
    void aChangedBodyPathOrQueryNoLongerMatchesTheSignature() throws IOException {
        String tampered = BODY.replace("hi", "pay now");
        MockHttpServletRequest otherPath = signed(post(BODY), NOW, "nonce-path-000001", BODY);
        otherPath.setRequestURI("/v1/notifications/bulk");
        MockHttpServletRequest otherQuery = signed(post(BODY), NOW, "nonce-query-00001", BODY);
        otherQuery.setQueryString("dryRun=true");

        assertThat(gate.admit(signed(post(tampered), NOW, "nonce-tampered-001", BODY), CLIENT, optional).refusal())
                .isEqualTo(ErrorCode.SIGNATURE_INVALID);
        assertThat(gate.admit(otherPath, CLIENT, optional).refusal()).isEqualTo(ErrorCode.SIGNATURE_INVALID);
        assertThat(gate.admit(otherQuery, CLIENT, optional).refusal()).isEqualTo(ErrorCode.SIGNATURE_INVALID);
    }

    @Test
    void anInvalidSignatureDoesNotUseUpTheNonce() throws IOException {
        MockHttpServletRequest forged = signed(post(BODY), NOW, "nonce-forged-00001", BODY);
        forged.removeHeader(RequestSignature.SIGNATURE_HEADER);
        forged.addHeader(RequestSignature.SIGNATURE_HEADER, "0".repeat(64));

        gate.admit(forged, CLIENT, optional);

        assertThat(nonces.used).isEmpty();
        assertThat(gate.admit(signed(post(BODY), NOW, "nonce-forged-00001", BODY), CLIENT, optional).admitted()).isTrue();
    }

    @Test
    void aTimestampOutsideTheWindowIsRefusedInEitherDirection() throws IOException {
        Instant stale = NOW.minus(WINDOW).minusSeconds(1);
        Instant early = NOW.plus(WINDOW).plusSeconds(1);

        assertThat(gate.admit(signed(post(BODY), stale, "nonce-stale-00001", BODY), CLIENT, optional).refusal())
                .isEqualTo(ErrorCode.SIGNATURE_EXPIRED);
        assertThat(gate.admit(signed(post(BODY), early, "nonce-early-00001", BODY), CLIENT, optional).refusal())
                .isEqualTo(ErrorCode.SIGNATURE_EXPIRED);
        assertThat(gate.admit(signed(post(BODY), NOW.minus(WINDOW), "nonce-edge-000001", BODY), CLIENT, optional)
                .admitted()).isTrue();
    }

    @Test
    void theNonceIsRememberedUntilItsTimestampLeavesTheWindow() throws IOException {
        Instant signedAt = NOW.minusSeconds(100);

        gate.admit(signed(post(BODY), signedAt, "nonce-expiry-00001", BODY), CLIENT, optional);

        assertThat(nonces.used).containsEntry(CLIENT + "/nonce-expiry-00001", signedAt.plus(WINDOW));
    }

    @Test
    void aSignatureFromAClientWithoutASecretIsRefused() throws IOException {
        Admission admission = gate.admit(signed(post(BODY), NOW, "nonce-nosecret-01", BODY), CLIENT, SigningPolicy.NONE);

        assertThat(admission.refusal()).isEqualTo(ErrorCode.SIGNATURE_INVALID);
    }

    @Test
    void malformedSignatureHeadersAreRefusedBeforeTheBodyIsRead() throws IOException {
        MockHttpServletRequest shortNonce = signed(post(BODY), NOW, "short", BODY);
        MockHttpServletRequest isoTimestamp = post(BODY);
        isoTimestamp.addHeader(RequestSignature.TIMESTAMP_HEADER, NOW.toString());
        isoTimestamp.addHeader(RequestSignature.NONCE_HEADER, "nonce-iso-0000001");
        isoTimestamp.addHeader(RequestSignature.SIGNATURE_HEADER, "0".repeat(64));

        assertThat(gate.admit(shortNonce, CLIENT, optional).refusal()).isEqualTo(ErrorCode.SIGNATURE_INVALID);
        assertThat(gate.admit(isoTimestamp, CLIENT, optional).refusal()).isEqualTo(ErrorCode.SIGNATURE_INVALID);
    }

    @Test
    void aSignedBodyLargerThanTheLimitIsRefusedAsTooLarge() throws IOException {
        String large = "x".repeat(2048);

        Admission admission = gate.admit(signed(post(large), NOW, "nonce-large-00001", large), CLIENT, optional);

        assertThat(admission.refusal()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE);
    }

    private static MockHttpServletRequest post(String body) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/notifications");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }

    private static MockHttpServletRequest signed(MockHttpServletRequest request, Instant at, String nonce, String signedBody) {
        String timestamp = Long.toString(at.getEpochSecond());
        String canonical = RequestSignature.canonical(timestamp, nonce, request.getMethod(), request.getRequestURI(),
                request.getQueryString(), signedBody.getBytes(StandardCharsets.UTF_8));
        request.addHeader(RequestSignature.TIMESTAMP_HEADER, timestamp);
        request.addHeader(RequestSignature.NONCE_HEADER, nonce);
        request.addHeader(RequestSignature.SIGNATURE_HEADER, RequestSignature.sign(SECRET, canonical));
        return request;
    }

    /** Behaves as the database store does for a single node: a live nonce is refused, an expired one taken over. */
    private static final class InMemoryNonces implements NonceStore {

        private final Map<String, Instant> used = new HashMap<>();

        @Override
        public boolean claim(UUID clientId, String nonce, Instant now, Instant expiresAt) {
            String key = clientId + "/" + nonce;
            Instant existing = used.get(key);
            if (existing != null && existing.isAfter(now)) {
                return false;
            }
            used.put(key, expiresAt);
            return true;
        }

        @Override
        public int purgeExpired(Instant now, int limit) {
            return 0;
        }
    }
}
