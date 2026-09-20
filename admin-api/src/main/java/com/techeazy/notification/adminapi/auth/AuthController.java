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

import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Sign-in, sign-out and account security for administrators. Only {@code /login} is reachable without a session. */
@RestController
@RequestMapping("/api/admin/auth")
class AuthController {

    record LoginRequest(@NotBlank String username, @NotBlank String password, String verificationCode) {}

    record LoginResponse(String token, Instant expiresAt, boolean twoFactorEnabled, boolean initialPassword) {}

    record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}

    record Reauthentication(@NotBlank String password, String verificationCode) {}

    record Code(@NotBlank String code) {}

    record RecoveryCodes(List<String> recoveryCodes) {}

    private final AdminAuthService auth;

    AuthController(AdminAuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/login")
    LoginResponse login(@RequestBody @jakarta.validation.Valid LoginRequest request) {
        AdminAuthService.Login login = auth.login(request.username(), request.password(), request.verificationCode());
        return new LoginResponse(login.token(), login.expiresAt(), login.twoFactorEnabled(), login.initialPassword());
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(Authentication authentication) {
        auth.logout(tokenHash(authentication));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    AdminAuthService.Account me(Authentication authentication) {
        return auth.account(authentication.getName());
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(Authentication authentication, @RequestBody @jakarta.validation.Valid PasswordChange request) {
        auth.changePassword(authentication.getName(), request.currentPassword(), request.newPassword(), tokenHash(authentication));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/2fa/setup")
    AdminAuthService.TwoFactorSetup startTwoFactor(Authentication authentication) {
        return auth.beginTwoFactor(authentication.getName());
    }

    @PostMapping("/2fa/enable")
    RecoveryCodes enableTwoFactor(Authentication authentication, @RequestBody @jakarta.validation.Valid Code request) {
        return new RecoveryCodes(auth.enableTwoFactor(authentication.getName(), request.code()));
    }

    @PostMapping("/2fa/disable")
    ResponseEntity<Void> disableTwoFactor(Authentication authentication, @RequestBody @jakarta.validation.Valid Reauthentication request) {
        auth.disableTwoFactor(authentication.getName(), request.password(), request.verificationCode());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/2fa/recovery-codes")
    RecoveryCodes newRecoveryCodes(Authentication authentication, @RequestBody @jakarta.validation.Valid Reauthentication request) {
        return new RecoveryCodes(auth.regenerateRecoveryCodes(authentication.getName(), request.password(), request.verificationCode()));
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> refused(AuthException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "INVALID_REQUEST", "message", "All required fields must be filled in"));
    }

    private static String tokenHash(Authentication authentication) {
        return authentication.getDetails() instanceof String hash ? hash : null;
    }
}
