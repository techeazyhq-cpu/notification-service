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
import java.util.Locale;

/**
 * A keyed fingerprint of a recipient address: HMAC-SHA256 of the normalised address (ADR-035). It is kept when the
 * address itself is erased, so a tenant can still find what it sent to a person who complains to an authority, by
 * giving the same address again; the address cannot be read back from it.
 *
 * <p>The key is derived from the personal-data key with its own label, so the two keys are independent although
 * only one secret is configured. Changing the personal-data key therefore also makes earlier fingerprints unfindable.
 */
public final class RecipientFingerprints {

    private static final String ALGORITHM = "HmacSHA256";
    private static final byte[] LABEL = "notification/recipient-fingerprint/v1".getBytes(StandardCharsets.UTF_8);

    private final SecretKeySpec key;

    public RecipientFingerprints(String personalDataKey) {
        this.key = new SecretKeySpec(hmac(new SecretKeySpec(personalDataKey.getBytes(StandardCharsets.UTF_8), ALGORITHM),
                LABEL), ALGORITHM);
    }

    /** The fingerprint of {@code recipient}: 64 hexadecimal characters, the same for every spelling of it. */
    public String of(String recipient) {
        return HexFormat.of().formatHex(hmac(key, normalize(recipient).getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * The form two spellings of one address share. An e-mail address is compared without case; a phone number
     * without spaces, dashes, dots or brackets, so {@code +1 (415) 555-0123} is {@code +14155550123}; anything else,
     * such as a device token, as given apart from surrounding blanks.
     */
    static String normalize(String recipient) {
        String trimmed = recipient.strip();
        if (trimmed.contains("@")) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
        String digits = trimmed.replaceAll("[\\s\\-().]", "");
        return digits.matches("\\+?[0-9]+") ? digits : trimmed;
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
