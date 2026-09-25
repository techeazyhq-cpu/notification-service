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

import org.springframework.security.crypto.password.PasswordEncoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

/**
 * Administrator sign-in and account security: password login with optional TOTP two-factor authentication,
 * one-time recovery codes, password changes, and revocable sessions.
 *
 * <p>Rules that matter: a wrong password and an unknown user look identical (including timing); an account locks
 * for a while after too many failures; a one-time code or recovery code works once; every sensitive change
 * (password, disabling two-factor, new recovery codes) needs the current password, and disabling two-factor or
 * replacing recovery codes also needs a valid code; changing the password signs out every other session.
 * Session tokens are random, sent as Bearer tokens, and stored only as SHA-256 hashes.
 */
public class AdminAuthService {

    private static final int RECOVERY_CODE_COUNT = 10;
    private static final int RECOVERY_CODE_LENGTH = 10;
    private static final String RECOVERY_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(1);

    record Login(String token, Instant expiresAt, boolean twoFactorEnabled, boolean initialPassword) {}

    record Account(String username, boolean twoFactorEnabled, int recoveryCodesRemaining, boolean initialPassword) {}

    record TwoFactorSetup(String secret, String otpauthUri) {}

    public record AuthenticatedSession(String username, String tokenHash) {}

    private final AdminUserStore users;
    private final SessionStore sessions;
    private final PasswordEncoder encoder;
    private final SecretCipher cipher;
    private final Totp totp;
    private final Clock clock;
    private final AuthSettings settings;
    private final SecureRandom random;
    private final String decoyHash;

    AdminAuthService(AdminUserStore users, SessionStore sessions, PasswordEncoder encoder, SecretCipher cipher, Totp totp,
                     Clock clock, AuthSettings settings, SecureRandom random) {
        this.users = users;
        this.sessions = sessions;
        this.encoder = encoder;
        this.cipher = cipher;
        this.totp = totp;
        this.clock = clock;
        this.settings = settings;
        this.random = random;
        this.decoyHash = encoder.encode(UUID.randomUUID().toString());
    }

    /** @param beforeCreating runs only when an administrator is actually about to be created (an empty user store) */
    void bootstrap(String username, String password, Runnable beforeCreating) {
        if (users.count() == 0) {
            beforeCreating.run();
            users.insert(new AdminUser(UUID.randomUUID(), username, encoder.encode(password), null, null, false, 0, null), clock.instant());
        }
    }

    Login login(String username, String password, String verificationCode) {
        Instant now = clock.instant();
        Optional<AdminUser> found = users.findByUsername(username == null ? "" : username);
        if (found.isEmpty()) {
            encoder.matches(password == null ? "" : password, decoyHash);
            throw AuthException.invalidCredentials();
        }
        AdminUser user = found.get();
        if (user.lockedAt(now)) {
            throw AuthException.locked();
        }
        if (password == null || !encoder.matches(password, user.passwordHash())) {
            recordFailure(user, now);
            throw AuthException.invalidCredentials();
        }
        if (user.totpEnabled()) {
            if (verificationCode == null || verificationCode.isBlank()) {
                throw AuthException.otpRequired();
            }
            if (!secondFactorAccepted(user, verificationCode, now)) {
                recordFailure(user, now);
                throw AuthException.invalidCredentials();
            }
        }
        users.clearFailures(user.id());
        return openSession(user, now);
    }

    void logout(String tokenHash) {
        sessions.delete(tokenHash);
    }

    public Optional<AuthenticatedSession> authenticate(String token) {
        String tokenHash = hashToken(token);
        Instant now = clock.instant();
        Optional<SessionStore.Session> found = sessions.find(tokenHash);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        SessionStore.Session session = found.get();
        if (!session.expiresAt().isAfter(now)) {
            sessions.delete(tokenHash);
            return Optional.empty();
        }
        if (Duration.between(session.lastSeenAt(), now).compareTo(TOUCH_INTERVAL) > 0) {
            sessions.touch(tokenHash, now, expiryFor(session.createdAt(), now));
        }
        return Optional.of(new AuthenticatedSession(session.username(), tokenHash));
    }

    Account account(String username) {
        AdminUser user = require(username);
        int remaining = user.totpEnabled() ? users.remainingRecoveryCodes(user.id()) : 0;
        return new Account(user.username(), user.totpEnabled(), remaining, user.passwordChangedAt() == null);
    }

    void changePassword(String username, String currentPassword, String newPassword, String currentTokenHash) {
        AdminUser user = require(username);
        Instant now = clock.instant();
        requireUnlocked(user, now);
        if (currentPassword == null || !encoder.matches(currentPassword, user.passwordHash())) {
            recordFailure(user, now);
            throw AuthException.reauthenticationFailed();
        }
        String violation = PasswordPolicy.violation(newPassword, user.username());
        if (violation != null) {
            throw AuthException.invalid(violation);
        }
        if (newPassword.equals(currentPassword)) {
            throw AuthException.invalid("The new password must be different from the current one");
        }
        users.updatePassword(user.id(), encoder.encode(newPassword), now);
        users.clearFailures(user.id());
        sessions.deleteAllExcept(user.id(), currentTokenHash);
    }

    TwoFactorSetup beginTwoFactor(String username) {
        AdminUser user = require(username);
        if (user.totpEnabled()) {
            throw AuthException.conflict("Two-factor authentication is already enabled");
        }
        String secret = totp.newSecret();
        users.saveTotp(user.id(), cipher.encrypt(secret), false);
        return new TwoFactorSetup(secret, totp.provisioningUri(settings.issuer(), user.username(), secret));
    }

    List<String> enableTwoFactor(String username, String code) {
        AdminUser user = require(username);
        if (user.totpEnabled()) {
            throw AuthException.conflict("Two-factor authentication is already enabled");
        }
        if (user.totpSecret() == null) {
            throw AuthException.conflict("Start the two-factor setup first");
        }
        OptionalLong step = totp.verify(cipher.decrypt(user.totpSecret()), code == null ? null : code.trim(),
                clock.millis(), user.totpLastStep());
        if (step.isEmpty() || !users.advanceTotpStep(user.id(), step.getAsLong())) {
            throw AuthException.invalid("The code does not match; check the code and the clock on your device");
        }
        users.saveTotp(user.id(), user.totpSecret(), true);
        return newRecoveryCodes(user);
    }

    void disableTwoFactor(String username, String password, String code) {
        AdminUser user = require(username);
        requireReauthentication(user, password, code);
        if (!user.totpEnabled()) {
            throw AuthException.conflict("Two-factor authentication is not enabled");
        }
        users.saveTotp(user.id(), null, false);
        users.replaceRecoveryCodes(user.id(), List.of());
    }

    List<String> regenerateRecoveryCodes(String username, String password, String code) {
        AdminUser user = require(username);
        requireReauthentication(user, password, code);
        if (!user.totpEnabled()) {
            throw AuthException.conflict("Two-factor authentication is not enabled");
        }
        return newRecoveryCodes(user);
    }

    int purgeExpiredSessions() {
        return sessions.purgeExpired(clock.instant());
    }

    static String hashToken(String token) {
        return sha256Hex(token);
    }

    private AdminUser require(String username) {
        return users.findByUsername(username).orElseThrow(AuthException::invalidCredentials);
    }

    private Login openSession(AdminUser user, Instant now) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expiresAt = expiryFor(now, now);
        sessions.create(hashToken(token), user.id(), now, expiresAt);
        return new Login(token, expiresAt, user.totpEnabled(), user.passwordChangedAt() == null);
    }

    private Instant expiryFor(Instant createdAt, Instant now) {
        Instant idle = now.plus(settings.idleTimeout());
        Instant absolute = createdAt.plus(settings.maxSessionAge());
        return idle.isBefore(absolute) ? idle : absolute;
    }

    private void requireUnlocked(AdminUser user, Instant now) {
        if (user.lockedAt(now)) {
            throw AuthException.locked();
        }
    }

    private void requireReauthentication(AdminUser user, String password, String code) {
        Instant now = clock.instant();
        requireUnlocked(user, now);
        boolean passwordOk = password != null && encoder.matches(password, user.passwordHash());
        if (!passwordOk || (user.totpEnabled() && !secondFactorAccepted(user, code, now))) {
            recordFailure(user, now);
            throw AuthException.reauthenticationFailed();
        }
        users.clearFailures(user.id());
    }

    private boolean secondFactorAccepted(AdminUser user, String code, Instant now) {
        if (code == null || code.isBlank() || user.totpSecret() == null) {
            return false;
        }
        String trimmed = code.trim();
        if (trimmed.matches("\\d{6}")) {
            OptionalLong step = totp.verify(cipher.decrypt(user.totpSecret()), trimmed, now.toEpochMilli(), user.totpLastStep());
            return step.isPresent() && users.advanceTotpStep(user.id(), step.getAsLong());
        }
        return users.consumeRecoveryCode(user.id(), recoveryHash(trimmed), now);
    }

    private void recordFailure(AdminUser user, Instant now) {
        users.recordFailure(user.id(), settings.maxFailedAttempts(), now.plus(settings.lockout()));
    }

    private List<String> newRecoveryCodes(AdminUser user) {
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODE_COUNT; i++) {
            codes.add(randomRecoveryCode());
        }
        users.replaceRecoveryCodes(user.id(), codes.stream().map(AdminAuthService::recoveryHash).toList());
        return codes;
    }

    private String randomRecoveryCode() {
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < RECOVERY_CODE_LENGTH; i++) {
            if (i == RECOVERY_CODE_LENGTH / 2) {
                code.append('-');
            }
            code.append(RECOVERY_ALPHABET.charAt(random.nextInt(RECOVERY_ALPHABET.length())));
        }
        return code.toString();
    }

    private static String recoveryHash(String code) {
        return sha256Hex(code.toUpperCase().replaceAll("[^A-Z0-9]", ""));
    }

    private static String sha256Hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
