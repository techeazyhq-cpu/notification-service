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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.OptionalLong;

/**
 * Time-based one-time passwords (RFC 6238): HMAC-SHA1, 30-second steps, six digits, the format every authenticator
 * app supports. A code is accepted for the current step and one step either side to tolerate clock drift.
 * Callers pass the last step they accepted so a code can never be used twice.
 */
final class Totp {

    static final int STEP_SECONDS = 30;
    private static final int DIGITS = 6;
    private static final int MODULUS = 1_000_000;
    private static final int WINDOW = 1;
    private static final int SECRET_BYTES = 20;

    private final SecureRandom random;

    Totp(SecureRandom random) {
        this.random = random;
    }

    String newSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return Base32.encode(secret);
    }

    String provisioningUri(String issuer, String account, String base32Secret) {
        return "otpauth://totp/" + encode(issuer) + ":" + encode(account) + "?secret=" + base32Secret
                + "&issuer=" + encode(issuer) + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    @SuppressWarnings("java:S4790")
    String codeAt(String base32Secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(Base32.decode(base32Secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%0" + DIGITS + "d", binary % MODULUS);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is unavailable", e);
        }
    }

    long stepAt(long epochMillis) {
        return epochMillis / 1000 / STEP_SECONDS;
    }

    /** The step the code belongs to, if the code is valid now and that step is newer than {@code lastAcceptedStep}. */
    OptionalLong verify(String base32Secret, String code, long epochMillis, long lastAcceptedStep) {
        if (code == null || !code.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long current = stepAt(epochMillis);
        OptionalLong match = OptionalLong.empty();
        for (long step = current - WINDOW; step <= current + WINDOW; step++) {
            if (step > lastAcceptedStep && constantTimeEquals(codeAt(base32Secret, step), code)) {
                match = OptionalLong.of(step);
            }
        }
        return match;
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
