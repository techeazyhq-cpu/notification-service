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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AdminAuthServiceTest {

    private static final String PASSWORD = "initial-password";
    private static final String NEW_PASSWORD = "a much better password";

    private final Totp totp = new Totp(new SecureRandom());
    private InMemoryStores.Users users;
    private InMemoryStores.Sessions sessions;
    private MutableClock clock;
    private AdminAuthService auth;

    @BeforeEach
    void setUp() {
        users = new InMemoryStores.Users();
        sessions = new InMemoryStores.Sessions(users);
        clock = new MutableClock(Instant.parse("2026-09-20T10:00:00Z"));
        AuthSettings settings = new AuthSettings("Notification Admin", Duration.ofMinutes(30), Duration.ofHours(12), 3, Duration.ofMinutes(15));
        auth = new AdminAuthService(users, sessions, PasswordEncoderFactories.createDelegatingPasswordEncoder(),
                new SecretCipher("test-key", new SecureRandom()), totp, clock, settings, new SecureRandom());
        auth.bootstrap("admin", PASSWORD);
    }

    @Test
    void bootstrapCreatesTheFirstAdministratorOnlyOnce() {
        auth.bootstrap("someone-else", "another-password");

        assertThat(users.count()).isEqualTo(1);
        assertThat(auth.account("admin").initialPassword()).isTrue();
    }

    @Test
    void loginWithTheRightPasswordOpensASessionThatAuthenticates() {
        AdminAuthService.Login login = auth.login("admin", PASSWORD, null);

        assertThat(login.twoFactorEnabled()).isFalse();
        assertThat(login.role()).isEqualTo(AdminRole.ADMIN);
        assertThat(auth.authenticate(login.token())).get().extracting(AdminAuthService.AuthenticatedSession::username).isEqualTo("admin");
        assertThat(auth.authenticate("not-a-token")).isEmpty();
    }

    @Test
    void bootstrapCreatesTheFirstAdministratorWithTheAdminRole() {
        assertThat(auth.listAdmins()).singleElement().extracting(AdminAuthService.AdminSummary::role).isEqualTo(AdminRole.ADMIN);
    }

    @Test
    void createAdminAddsAViewerOrOperatorWhoCanSignIn() {
        auth.createAdmin("viewer-1", "a decent password", AdminRole.VIEWER);

        AdminAuthService.Login login = auth.login("viewer-1", "a decent password", null);

        assertThat(login.role()).isEqualTo(AdminRole.VIEWER);
        assertThat(auth.listAdmins()).extracting(AdminAuthService.AdminSummary::username).contains("admin", "viewer-1");
    }

    @Test
    void createAdminRejectsADuplicateUsernameOrAWeakPassword() {
        auth.createAdmin("operator-1", "a decent password", AdminRole.OPERATOR);

        assertThat(catchAuth(() -> auth.createAdmin("operator-1", "another decent password", AdminRole.OPERATOR)).code())
                .isEqualTo("INVALID_STATE");
        assertThat(catchAuth(() -> auth.createAdmin("operator-2", "short", AdminRole.OPERATOR)).code())
                .isEqualTo("INVALID_REQUEST");
    }

    @Test
    void authenticateReflectsARoleChangeWithoutRequiringSignInAgain() {
        AdminAuthService.AdminSummary viewer = auth.createAdmin("viewer-2", "a decent password", AdminRole.VIEWER);
        String token = auth.login("viewer-2", "a decent password", null).token();
        assertThat(auth.authenticate(token)).get().extracting(AdminAuthService.AuthenticatedSession::role).isEqualTo(AdminRole.VIEWER);

        auth.changeRole(viewer.id(), AdminRole.OPERATOR);

        assertThat(auth.authenticate(token)).get().extracting(AdminAuthService.AuthenticatedSession::role).isEqualTo(AdminRole.OPERATOR);
    }

    @Test
    void changeRoleAndDeleteAdminProtectTheLastAdministrator() {
        assertThat(catchAuth(() -> auth.changeRole(adminId(), AdminRole.VIEWER)).code()).isEqualTo("INVALID_STATE");
        assertThat(catchAuth(() -> auth.deleteAdmin("admin", adminId())).code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void deleteAdminRefusesToDeleteYourOwnAccountEvenWhenNotTheLastOne() {
        auth.createAdmin("admin-2", "a decent password", AdminRole.ADMIN);

        assertThat(catchAuth(() -> auth.deleteAdmin("admin", adminId())).code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void deleteAdminRemovesASecondAdministrator() {
        AdminAuthService.AdminSummary second = auth.createAdmin("admin-3", "a decent password", AdminRole.ADMIN);

        auth.deleteAdmin("admin", second.id());

        assertThat(auth.listAdmins()).extracting(AdminAuthService.AdminSummary::username).doesNotContain("admin-3");
        assertThat(catchAuth(() -> auth.login("admin-3", "a decent password", null)).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    private UUID adminId() {
        return auth.listAdmins().stream().filter(a -> a.username().equals("admin")).findFirst().orElseThrow().id();
    }

    @Test
    void wrongPasswordAndUnknownUserAreIndistinguishable() {
        AuthException wrong = catchAuth(() -> auth.login("admin", "nope", null));
        AuthException unknown = catchAuth(() -> auth.login("ghost", "nope", null));

        assertThat(wrong.code()).isEqualTo(unknown.code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(wrong.getMessage()).isEqualTo(unknown.getMessage());
        assertThat(wrong.status()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void theAccountLocksAfterTooManyFailuresAndUnlocksLater() {
        for (int i = 0; i < 3; i++) {
            catchAuth(() -> auth.login("admin", "nope", null));
        }

        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, null)).code()).isEqualTo("ACCOUNT_LOCKED");

        clock.advance(Duration.ofMinutes(16));

        assertThat(auth.login("admin", PASSWORD, null).token()).isNotBlank();
    }

    @Test
    void aSessionExpiresWhenIdleAndNeverOutlivesItsMaximumAge() {
        String token = auth.login("admin", PASSWORD, null).token();

        clock.advance(Duration.ofMinutes(20));
        assertThat(auth.authenticate(token)).isPresent();
        clock.advance(Duration.ofMinutes(20));
        assertThat(auth.authenticate(token)).isPresent();
        clock.advance(Duration.ofMinutes(31));
        assertThat(auth.authenticate(token)).isEmpty();

        String longLived = auth.login("admin", PASSWORD, null).token();
        for (int i = 0; i < 26; i++) {
            clock.advance(Duration.ofMinutes(29));
            if (auth.authenticate(longLived).isEmpty()) {
                assertThat(Duration.ofMinutes(29L * i)).isLessThanOrEqualTo(Duration.ofHours(12));
                return;
            }
        }
        throw new AssertionError("The session should have ended at its maximum age");
    }

    @Test
    void logoutEndsOnlyThatSession() {
        String first = auth.login("admin", PASSWORD, null).token();
        String second = auth.login("admin", PASSWORD, null).token();

        auth.logout(AdminAuthService.hashToken(first));

        assertThat(auth.authenticate(first)).isEmpty();
        assertThat(auth.authenticate(second)).isPresent();
    }

    @Test
    void changingThePasswordNeedsTheCurrentOneAndSignsOutOtherSessions() {
        String keep = auth.login("admin", PASSWORD, null).token();
        String other = auth.login("admin", PASSWORD, null).token();

        auth.changePassword("admin", PASSWORD, NEW_PASSWORD, AdminAuthService.hashToken(keep));

        assertThat(auth.authenticate(keep)).isPresent();
        assertThat(auth.authenticate(other)).isEmpty();
        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, null)).code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(auth.login("admin", NEW_PASSWORD, null).token()).isNotBlank();
        assertThat(auth.account("admin").initialPassword()).isFalse();
    }

    @Test
    void aPasswordChangeIsRefusedForAWrongCurrentPasswordWeakOrUnchangedPassword() {
        assertThat(catchAuth(() -> auth.changePassword("admin", "wrong", NEW_PASSWORD, null)).code()).isEqualTo("REAUTHENTICATION_FAILED");
        assertThat(catchAuth(() -> auth.changePassword("admin", PASSWORD, "short", null)).code()).isEqualTo("INVALID_REQUEST");
        assertThat(catchAuth(() -> auth.changePassword("admin", PASSWORD, "admin-admin-admin", null)).getMessage()).contains("user name");
        assertThat(catchAuth(() -> auth.changePassword("admin", PASSWORD, PASSWORD, null)).getMessage()).contains("different");
        assertThat(auth.login("admin", PASSWORD, null).token()).isNotBlank();
    }

    @Test
    void repeatedWrongCurrentPasswordsLockThePasswordChange() {
        for (int i = 0; i < 3; i++) {
            catchAuth(() -> auth.changePassword("admin", "wrong", NEW_PASSWORD, null));
        }

        assertThat(catchAuth(() -> auth.changePassword("admin", PASSWORD, NEW_PASSWORD, null)).code()).isEqualTo("ACCOUNT_LOCKED");
    }

    @Test
    void enablingTwoFactorNeedsAValidCodeAndReturnsTenRecoveryCodes() {
        AdminAuthService.TwoFactorSetup setup = auth.beginTwoFactor("admin");

        assertThat(setup.otpauthUri()).contains(setup.secret());
        assertThat(catchAuth(() -> auth.enableTwoFactor("admin", "000000")).code()).isEqualTo("INVALID_REQUEST");
        assertThat(auth.account("admin").twoFactorEnabled()).isFalse();

        List<String> codes = auth.enableTwoFactor("admin", currentCode(setup.secret()));

        assertThat(codes).hasSize(10).allMatch(c -> c.matches("[A-Z2-9]{5}-[A-Z2-9]{5}")).doesNotHaveDuplicates();
        assertThat(auth.account("admin")).extracting(AdminAuthService.Account::twoFactorEnabled, AdminAuthService.Account::recoveryCodesRemaining)
                .containsExactly(true, 10);
    }

    @Test
    void enablingRequiresTheSetupStepAndCannotBeDoneTwice() {
        assertThat(catchAuth(() -> auth.enableTwoFactor("admin", "123456")).code()).isEqualTo("INVALID_STATE");

        AdminAuthService.TwoFactorSetup setup = enableTwoFactor();

        assertThat(catchAuth(() -> auth.beginTwoFactor("admin")).code()).isEqualTo("INVALID_STATE");
        assertThat(catchAuth(() -> auth.enableTwoFactor("admin", currentCode(setup.secret()))).code()).isEqualTo("INVALID_STATE");
    }

    @Test
    void withTwoFactorOnLoginNeedsTheCodeAndAWrongOneIsRefused() {
        AdminAuthService.TwoFactorSetup setup = enableTwoFactor();
        clock.advance(Duration.ofSeconds(60));

        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, null)).code()).isEqualTo("OTP_REQUIRED");
        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, "000000")).code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(auth.login("admin", PASSWORD, currentCode(setup.secret())).twoFactorEnabled()).isTrue();
    }

    @Test
    void aCodeCannotBeUsedTwice() {
        AdminAuthService.TwoFactorSetup setup = enableTwoFactor();
        clock.advance(Duration.ofSeconds(60));
        String code = currentCode(setup.secret());

        auth.login("admin", PASSWORD, code);

        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, code)).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void theCodeThatTurnedTwoFactorOnCannotImmediatelyBeUsedToSignIn() {
        AdminAuthService.TwoFactorSetup setup = auth.beginTwoFactor("admin");
        String code = currentCode(setup.secret());
        auth.enableTwoFactor("admin", code);

        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, code)).code()).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void askingForTheCodeDoesNotCountAsAFailedAttempt() {
        enableTwoFactor();
        clock.advance(Duration.ofSeconds(60));

        for (int i = 0; i < 5; i++) {
            assertThat(catchAuth(() -> auth.login("admin", PASSWORD, null)).code()).isEqualTo("OTP_REQUIRED");
        }
    }

    @Test
    void aRecoveryCodeSignsInOnceAndIsUsedUp() {
        AdminAuthService.TwoFactorSetup setup = auth.beginTwoFactor("admin");
        List<String> codes = auth.enableTwoFactor("admin", currentCode(setup.secret()));

        assertThat(auth.login("admin", PASSWORD, codes.get(0).toLowerCase().replace("-", " ")).token()).isNotBlank();
        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, codes.get(0))).code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(auth.account("admin").recoveryCodesRemaining()).isEqualTo(9);
    }

    @Test
    void disablingTwoFactorNeedsThePasswordAndACodeAndClearsEverything() {
        AdminAuthService.TwoFactorSetup setup = enableTwoFactor();
        clock.advance(Duration.ofSeconds(60));

        assertThat(catchAuth(() -> auth.disableTwoFactor("admin", "wrong", currentCode(setup.secret()))).code()).isEqualTo("REAUTHENTICATION_FAILED");
        assertThat(catchAuth(() -> auth.disableTwoFactor("admin", PASSWORD, "000000")).code()).isEqualTo("REAUTHENTICATION_FAILED");

        auth.disableTwoFactor("admin", PASSWORD, currentCode(setup.secret()));

        assertThat(auth.account("admin")).extracting(AdminAuthService.Account::twoFactorEnabled, AdminAuthService.Account::recoveryCodesRemaining)
                .containsExactly(false, 0);
        assertThat(auth.login("admin", PASSWORD, null).token()).isNotBlank();
    }

    @Test
    void newRecoveryCodesReplaceTheOldOnes() {
        AdminAuthService.TwoFactorSetup setup = auth.beginTwoFactor("admin");
        List<String> old = auth.enableTwoFactor("admin", currentCode(setup.secret()));
        clock.advance(Duration.ofSeconds(60));

        List<String> fresh = auth.regenerateRecoveryCodes("admin", PASSWORD, currentCode(setup.secret()));

        assertThat(fresh).hasSize(10).doesNotContainAnyElementsOf(old);
        assertThat(catchAuth(() -> auth.login("admin", PASSWORD, old.get(0))).code()).isEqualTo("INVALID_CREDENTIALS");
        assertThat(auth.login("admin", PASSWORD, fresh.get(0)).token()).isNotBlank();
    }

    @Test
    void purgingRemovesOnlyExpiredSessions() {
        auth.login("admin", PASSWORD, null);
        clock.advance(Duration.ofMinutes(31));
        String live = auth.login("admin", PASSWORD, null).token();

        assertThat(auth.purgeExpiredSessions()).isEqualTo(1);
        assertThat(sessions.size()).isEqualTo(1);
        assertThat(auth.authenticate(live)).isPresent();
    }

    private AdminAuthService.TwoFactorSetup enableTwoFactor() {
        AdminAuthService.TwoFactorSetup setup = auth.beginTwoFactor("admin");
        auth.enableTwoFactor("admin", currentCode(setup.secret()));
        return setup;
    }

    private String currentCode(String secret) {
        return totp.codeAt(secret, totp.stepAt(clock.millis()));
    }

    private static AuthException catchAuth(Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(AuthException.class, action::run);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
