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
import java.util.HexFormat;
import java.util.List;

/**
 * Fingerprints of what a client asked to send, so a repeated {@code Idempotency-Key} can be told apart from a
 * different request reusing it. The payload holds recipients and their variables, so the fingerprint is a keyed
 * HMAC under a key derived from the personal-data key: without that key it reveals nothing, not even whether two
 * requests went to the same person.
 */
public final class PayloadFingerprints {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] LABEL = "notification/idempotency-payload/v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;

    public PayloadFingerprints(String personalDataKey) {
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(personalDataKey.getBytes(StandardCharsets.UTF_8), ALGORITHM),
                LABEL), ALGORITHM);
    }

    /**
     * Fingerprints {@code fields} in order. Each field is length-prefixed and {@code null} is told apart from the empty
     * string, so no two different lists of fields share an encoding.
     */
    public String of(List<String> fields) {
        StringBuilder canonical = new StringBuilder();
        for (String field : fields) {
            if (field == null) {
                canonical.append("-;");
            } else {
                canonical.append(field.length()).append(':').append(field).append(';');
            }
        }
        return HexFormat.of().formatHex(hmac(key, canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(SecretKeySpec key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }
}
