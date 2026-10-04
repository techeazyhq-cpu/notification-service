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

import com.techeazy.notification.domain.FieldEncryptor;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Encrypts a value at rest with AES-256-GCM (ADR-037).
 *
 * <p>Each instance serves one {@code purpose}, such as provider settings or a client's signing secret. Its key is
 * derived from the configured key for that purpose alone, and the purpose is bound to every ciphertext as associated
 * data, so a value encrypted for one column cannot be decrypted as another even under the same configured key.
 *
 * <p>Ciphertext is written as {@code v2.<key id>.<base64 of IV and ciphertext>}. The key id names the configured key
 * that encrypted it, so keys can be rotated: the current key encrypts, and the current or any previous key decrypts
 * what it wrote; {@link #needsReencryption} finds what still has to be rewritten before a previous key is retired.
 * Values written before this format (plain base64, keyed by SHA-256 of the configured key, no associated data) are
 * still read, with the current or a previous key.
 */
public final class AesGcmCipher implements FieldEncryptor {

    /** The purpose of instances built without one, for tests and single-use tools. */
    public static final String GENERAL = "general";

    private static final String VERSION = "v2";
    private static final String SEPARATOR = ".";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final String HMAC = "HmacSHA256";

    /** One configured key: its id, its key for this purpose, and its key for values in the earlier format. */
    private record Key(String id, SecretKeySpec purposeKey, SecretKeySpec legacyKey) {
    }

    private final Key current;
    private final List<Key> all;
    private final byte[] associatedData;
    private final SecureRandom random;

    public AesGcmCipher(String passphrase) {
        this(passphrase, new SecureRandom());
    }

    public AesGcmCipher(String passphrase, SecureRandom random) {
        this(passphrase, List.of(), GENERAL, random);
    }

    /**
     * @param currentKey   the configured key new values are encrypted with
     * @param previousKeys earlier keys that still decrypt what they wrote, until it has been re-encrypted
     * @param purpose      what the values are, bound to each ciphertext; it must never change for a column
     */
    public AesGcmCipher(String currentKey, List<String> previousKeys, String purpose, SecureRandom random) {
        this.current = key(currentKey, purpose);
        List<Key> keys = new ArrayList<>();
        keys.add(current);
        previousKeys.stream().filter(previous -> previous != null && !previous.isBlank())
                .forEach(previous -> keys.add(key(previous, purpose)));
        this.all = List.copyOf(keys);
        this.associatedData = ("notification/" + purpose).getBytes(StandardCharsets.UTF_8);
        this.random = random;
    }

    @Override
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, current.purposeKey(), new GCMParameterSpec(TAG_BITS, iv));
            cipher.updateAAD(associatedData);
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            String body = Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
            return VERSION + SEPARATOR + current.id() + SEPARATOR + body;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the secret", e);
        }
    }

    @Override
    public String decrypt(String stored) {
        if (isVersioned(stored)) {
            String[] parts = stored.split("\\.", 3);
            Key key = all.stream().filter(k -> k.id().equals(parts[1])).findFirst()
                    .orElseThrow(() -> new IllegalStateException("The secret was encrypted with key " + parts[1]
                            + ", which is neither the current nor a configured previous key"));
            return open(parts[2], key.purposeKey(), associatedData);
        }
        IllegalStateException failure = null;
        for (Key key : all) {
            try {
                return open(stored, key.legacyKey(), null);
            } catch (IllegalStateException e) {
                failure = e;
            }
        }
        throw failure;
    }

    /** Whether {@code stored} was written in the earlier format or under a key other than the current one. */
    public boolean needsReencryption(String stored) {
        return !isVersioned(stored) || !stored.split("\\.", 3)[1].equals(current.id());
    }

    /** The id of the current key, as written into each ciphertext. */
    public String currentKeyId() {
        return current.id();
    }

    private static boolean isVersioned(String stored) {
        return stored.startsWith(VERSION + SEPARATOR) && stored.split("\\.", 3).length == 3;
    }

    private static String open(String body, SecretKeySpec key, byte[] associatedData) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(body));
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] encrypted = new byte[buffer.remaining()];
            buffer.get(encrypted);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            if (associatedData != null) {
                cipher.updateAAD(associatedData);
            }
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalStateException("Could not decrypt the secret; was the encryption key changed?", e);
        }
    }

    private static Key key(String configured, String purpose) {
        byte[] material = configured.getBytes(StandardCharsets.UTF_8);
        String id = HexFormat.of().formatHex(hmac(material, "notification/key-id")).substring(0, 8);
        return new Key(id, new SecretKeySpec(hmac(material, "notification/aes-gcm/v2/" + purpose), "AES"),
                new SecretKeySpec(sha256(material), "AES"));
    }

    private static byte[] hmac(byte[] key, String label) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key, HMAC));
            return mac.doFinal(label.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
