package com.techeazy.notification.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HexFormat;

/** API keys are random 256-bit secrets, so an unsalted SHA-256 is sufficient for lookup-by-hash. */
public final class ApiKeys {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PREFIX = "ntf_";

    private ApiKeys() {}

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + HexFormat.of().formatHex(bytes);
    }

    public static String hash(String apiKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Non-secret display prefix so admins can recognise a key. */
    public static String displayPrefix(String apiKey) {
        return apiKey.substring(0, Math.min(10, apiKey.length()));
    }
}
