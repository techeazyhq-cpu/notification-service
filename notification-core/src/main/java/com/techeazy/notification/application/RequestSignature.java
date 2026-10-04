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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The client API's request signature (ADR-036). A client signs each request with its signing secret, binding the
 * method, path, query and the exact body bytes to a timestamp and a single-use nonce, so a captured request can be
 * neither altered nor sent again.
 * <p>
 * The signed text is these lines joined by a line feed, with no trailing line feed:
 * <pre>
 * NS1-HMAC-SHA256
 * {timestamp: Unix seconds, as sent in X-Signature-Timestamp}
 * {nonce: as sent in X-Signature-Nonce}
 * {HTTP method, upper case}
 * {path, exactly as sent, without the query}
 * {query string, exactly as sent, without the '?'; empty if none}
 * {lower-case hex SHA-256 of the body bytes; of no bytes if there is no body}
 * </pre>
 * The signature is the lower-case hex HMAC-SHA256 of that text, keyed by the signing secret's UTF-8 bytes, and is
 * sent in X-Signature.
 */
public final class RequestSignature {

    public static final String SCHEME = "NS1-HMAC-SHA256";
    public static final String SIGNATURE_HEADER = "X-Signature";
    public static final String TIMESTAMP_HEADER = "X-Signature-Timestamp";
    public static final String NONCE_HEADER = "X-Signature-Nonce";

    private static final String HMAC = "HmacSHA256";
    private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9_-]{16,64}");
    private static final Pattern TIMESTAMP = Pattern.compile("\\d{1,12}");
    private static final Pattern SIGNATURE = Pattern.compile("[0-9a-f]{64}");
    private static final HexFormat HEX = HexFormat.of();

    private RequestSignature() {
    }

    /** The text a client signs; see the class description. */
    public static String canonical(String timestamp, String nonce, String method, String path, String query, byte[] body) {
        return String.join("\n", SCHEME, timestamp, nonce, method.toUpperCase(Locale.ROOT), path,
                query == null ? "" : query, sha256Hex(body));
    }

    /** The signature of {@code canonical} under {@code secret}, as a client computes it. */
    public static String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC));
            return HEX.formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** Whether {@code presented} is the signature of {@code canonical}, compared in constant time. */
    public static boolean matches(String secret, String canonical, String presented) {
        if (presented == null || !SIGNATURE.matcher(presented).matches()) {
            return false;
        }
        return MessageDigest.isEqual(sign(secret, canonical).getBytes(StandardCharsets.US_ASCII),
                presented.getBytes(StandardCharsets.US_ASCII));
    }

    /** A nonce is 16 to 64 characters of letters, digits, '-' and '_', such as a UUID or random base64url text. */
    public static boolean isWellFormedNonce(String nonce) {
        return nonce != null && NONCE.matcher(nonce).matches();
    }

    /** A timestamp is a whole number of seconds since the Unix epoch. */
    public static boolean isWellFormedTimestamp(String timestamp) {
        return timestamp != null && TIMESTAMP.matcher(timestamp).matches();
    }

    public static String sha256Hex(byte[] body) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(body == null ? new byte[0] : body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
