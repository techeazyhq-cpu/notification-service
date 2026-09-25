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

package com.techeazy.notification.adminapi;

import com.techeazy.notification.adminapi.auth.AdminAuthService;
import com.techeazy.notification.adminapi.auth.AdminRole;
import com.techeazy.notification.adminapi.auth.AuthException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Other administrator accounts: list, create, change role, remove. Always requires the ADMIN role (see ADR-015). */
@RestController
@RequestMapping("/api/admin/administrators")
class AdministratorsController {

    record NewAdministrator(@NotBlank @Size(min = 3, max = 64) String username, @NotBlank String password, @NotNull AdminRole role) {}

    record RoleChange(@NotNull AdminRole role) {}

    private final AdminAuthService auth;

    AdministratorsController(AdminAuthService auth) {
        this.auth = auth;
    }

    @GetMapping
    List<AdminAuthService.AdminSummary> list() {
        return auth.listAdmins();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    AdminAuthService.AdminSummary create(@Valid @RequestBody NewAdministrator request) {
        return auth.createAdmin(request.username(), request.password(), request.role());
    }

    @PutMapping("/{id}/role")
    ResponseEntity<Void> changeRole(@PathVariable UUID id, @Valid @RequestBody RoleChange request) {
        auth.changeRole(id, request.role());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
        auth.deleteAdmin(authentication.getName(), id);
        return ResponseEntity.noContent().build();
    }

    @ExceptionHandler(AuthException.class)
    ResponseEntity<Map<String, String>> refused(AuthException e) {
        return ResponseEntity.status(e.status()).body(Map.of("code", e.code(), "message", e.getMessage()));
    }

    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid() {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("code", "INVALID_REQUEST", "message", "All required fields must be filled in"));
    }
}
