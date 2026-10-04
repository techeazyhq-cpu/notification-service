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
import com.techeazy.notification.error.ErrorCode;
import com.techeazy.notification.infra.ClientSigningSecrets;
import com.techeazy.notification.port.NonceStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Admits or refuses a client API request by its signature (ADR-036), after the API key has identified the client.
 * <ul>
 *   <li>A request that carries any signature header is always verified, whether or not the client requires
 *       signatures, so a client can start signing before an administrator makes it mandatory.</li>
 *   <li>An unsigned request is refused only when the client requires signatures and the request changes state
 *       (POST, PUT, PATCH, DELETE). Reads stay unsigned, which keeps the client tracker's views working.</li>
 *   <li>The nonce is claimed only once the signature is valid, so forged requests cannot fill the nonce store.</li>
 * </ul>
 */
@Component
class RequestSignatureGate {

    /** The outcome: the request to pass on, which for a signed request holds its body; or why it was refused. */
    record Admission(HttpServletRequest request, ErrorCode refusal, String reason) {

        static Admission admit(HttpServletRequest request) {
            return new Admission(request, null, null);
        }

        static Admission refuse(ErrorCode refusal, String reason) {
            return new Admission(null, refusal, reason);
        }

        boolean admitted() {
            return refusal == null;
        }
    }

    /** What the gate needs to know about the client; the secret is still encrypted. */
    record SigningPolicy(String storedSecret, boolean required) {
        static final SigningPolicy NONE = new SigningPolicy(null, false);
    }

    private static final Set<String> STATE_CHANGING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final long BYTES_PER_MEGABYTE = 1024L * 1024L;
    private static final long MULTIPART_OVERHEAD_BYTES = BYTES_PER_MEGABYTE;

    private final NonceStore nonces;
    private final ClientSigningSecrets secrets;
    private final Clock clock;
    private final Duration window;
    private final long maxBodyBytes;

    @Autowired
    RequestSignatureGate(NonceStore nonces, ClientSigningSecrets secrets,
                         @Value("${client-api.signature-window-seconds:300}") long windowSeconds) {
        this(nonces, secrets, Clock.systemUTC(), Duration.ofSeconds(windowSeconds),
                CsvUploadConfig.MAX_UPLOAD_MEGABYTES * BYTES_PER_MEGABYTE + MULTIPART_OVERHEAD_BYTES);
    }

    RequestSignatureGate(NonceStore nonces, ClientSigningSecrets secrets, Clock clock, Duration window, long maxBodyBytes) {
        this.nonces = nonces;
        this.secrets = secrets;
        this.clock = clock;
        this.window = window;
        this.maxBodyBytes = maxBodyBytes;
    }

    Admission admit(HttpServletRequest request, UUID clientId, SigningPolicy policy) throws IOException {
        if (!isSigned(request)) {
            if (policy.required() && STATE_CHANGING.contains(request.getMethod())) {
                return Admission.refuse(ErrorCode.SIGNATURE_REQUIRED,
                        "This client requires signed requests; send the X-Signature headers");
            }
            return Admission.admit(request);
        }
        if (policy.storedSecret() == null) {
            return Admission.refuse(ErrorCode.SIGNATURE_INVALID,
                    "The request is signed, but this client has no signing secret");
        }
        String timestamp = request.getHeader(RequestSignature.TIMESTAMP_HEADER);
        String nonce = request.getHeader(RequestSignature.NONCE_HEADER);
        if (!RequestSignature.isWellFormedTimestamp(timestamp) || !RequestSignature.isWellFormedNonce(nonce)) {
            return Admission.refuse(ErrorCode.SIGNATURE_INVALID, "X-Signature-Timestamp must be Unix seconds and "
                    + "X-Signature-Nonce 16 to 64 letters, digits, '-' or '_'");
        }
        Instant now = clock.instant();
        Instant signedAt = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(signedAt, now).abs().compareTo(window) > 0) {
            return Admission.refuse(ErrorCode.SIGNATURE_EXPIRED,
                    "X-Signature-Timestamp is more than " + window.toSeconds() + " seconds from the service's clock");
        }
        CachedBodyRequest cached;
        try {
            cached = CachedBodyRequest.read(request, maxBodyBytes);
        } catch (CachedBodyRequest.BodyTooLargeException e) {
            return Admission.refuse(ErrorCode.PAYLOAD_TOO_LARGE, e.getMessage());
        }
        String canonical = RequestSignature.canonical(timestamp, nonce, request.getMethod(), request.getRequestURI(),
                request.getQueryString(), cached.body());
        if (!RequestSignature.matches(secrets.decryptForUse(policy.storedSecret()), canonical,
                request.getHeader(RequestSignature.SIGNATURE_HEADER))) {
            return Admission.refuse(ErrorCode.SIGNATURE_INVALID,
                    "The signature does not match the method, path, query and body of this request");
        }
        if (!nonces.claim(clientId, nonce, now, signedAt.plus(window))) {
            return Admission.refuse(ErrorCode.REQUEST_REPLAYED, "This X-Signature-Nonce was already used");
        }
        return Admission.admit(cached);
    }

    private static boolean isSigned(HttpServletRequest request) {
        return request.getHeader(RequestSignature.SIGNATURE_HEADER) != null
                || request.getHeader(RequestSignature.TIMESTAMP_HEADER) != null
                || request.getHeader(RequestSignature.NONCE_HEADER) != null;
    }
}
