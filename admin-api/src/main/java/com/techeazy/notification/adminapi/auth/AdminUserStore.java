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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistence port for administrators, their recovery codes and failed-attempt counters. */
interface AdminUserStore {

    long count();

    Optional<AdminUser> findByUsername(String username);

    Optional<AdminUser> findById(UUID id);

    List<AdminUser> findAll();

    void insert(AdminUser user, Instant now);

    void updateRole(UUID id, AdminRole role);

    boolean delete(UUID id);

    void updatePassword(UUID id, String passwordHash, Instant now);

    void saveTotp(UUID id, String encryptedSecret, boolean enabled);

    boolean advanceTotpStep(UUID id, long step);

    void recordFailure(UUID id, int maxAttempts, Instant lockedUntilWhenExceeded);

    void clearFailures(UUID id);

    void replaceRecoveryCodes(UUID id, List<String> codeHashes);

    boolean consumeRecoveryCode(UUID id, String codeHash, Instant now);

    int remainingRecoveryCodes(UUID id);
}
