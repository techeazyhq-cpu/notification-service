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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Fast in-memory versions of the persistence ports for service tests; the real SQL is tested separately. */
final class InMemoryStores {

    private InMemoryStores() {
    }

    static final class Users implements AdminUserStore {
        private final Map<UUID, AdminUser> byId = new HashMap<>();
        private final Map<UUID, Integer> failures = new HashMap<>();
        private final Map<UUID, Map<String, Boolean>> recovery = new HashMap<>();

        @Override
        public long count() {
            return byId.size();
        }

        @Override
        public Optional<AdminUser> findByUsername(String username) {
            return byId.values().stream().filter(u -> u.username().equals(username)).findFirst();
        }

        @Override
        public Optional<AdminUser> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<AdminUser> findAll() {
            return byId.values().stream().sorted(java.util.Comparator.comparing(AdminUser::username)).toList();
        }

        @Override
        public void insert(AdminUser user, Instant now) {
            byId.put(user.id(), user);
        }

        @Override
        public void updateRole(UUID id, AdminRole role) {
            AdminUser u = byId.get(id);
            byId.put(id, new AdminUser(id, u.username(), u.passwordHash(), u.passwordChangedAt(), u.totpSecret(), u.totpEnabled(), u.totpLastStep(), u.lockedUntil(), role));
        }

        @Override
        public boolean delete(UUID id) {
            return byId.remove(id) != null;
        }

        @Override
        public void updatePassword(UUID id, String passwordHash, Instant now) {
            AdminUser u = byId.get(id);
            byId.put(id, new AdminUser(id, u.username(), passwordHash, now, u.totpSecret(), u.totpEnabled(), u.totpLastStep(), u.lockedUntil(), u.role()));
        }

        @Override
        public void saveTotp(UUID id, String encryptedSecret, boolean enabled) {
            AdminUser u = byId.get(id);
            byId.put(id, new AdminUser(id, u.username(), u.passwordHash(), u.passwordChangedAt(), encryptedSecret, enabled, u.totpLastStep(), u.lockedUntil(), u.role()));
        }

        @Override
        public boolean advanceTotpStep(UUID id, long step) {
            AdminUser u = byId.get(id);
            if (u.totpLastStep() >= step) {
                return false;
            }
            byId.put(id, new AdminUser(id, u.username(), u.passwordHash(), u.passwordChangedAt(), u.totpSecret(), u.totpEnabled(), step, u.lockedUntil(), u.role()));
            return true;
        }

        @Override
        public void recordFailure(UUID id, int maxAttempts, Instant lockedUntilWhenExceeded) {
            int count = failures.merge(id, 1, Integer::sum);
            if (count >= maxAttempts) {
                failures.put(id, 0);
                AdminUser u = byId.get(id);
                byId.put(id, new AdminUser(id, u.username(), u.passwordHash(), u.passwordChangedAt(), u.totpSecret(), u.totpEnabled(), u.totpLastStep(), lockedUntilWhenExceeded, u.role()));
            }
        }

        @Override
        public void clearFailures(UUID id) {
            failures.remove(id);
            AdminUser u = byId.get(id);
            byId.put(id, new AdminUser(id, u.username(), u.passwordHash(), u.passwordChangedAt(), u.totpSecret(), u.totpEnabled(), u.totpLastStep(), null, u.role()));
        }

        @Override
        public void replaceRecoveryCodes(UUID id, List<String> codeHashes) {
            Map<String, Boolean> codes = new HashMap<>();
            codeHashes.forEach(h -> codes.put(h, false));
            recovery.put(id, codes);
        }

        @Override
        public boolean consumeRecoveryCode(UUID id, String codeHash, Instant now) {
            Map<String, Boolean> codes = recovery.getOrDefault(id, Map.of());
            if (Boolean.FALSE.equals(codes.get(codeHash))) {
                codes.put(codeHash, true);
                return true;
            }
            return false;
        }

        @Override
        public int remainingRecoveryCodes(UUID id) {
            return (int) recovery.getOrDefault(id, Map.of()).values().stream().filter(used -> !used).count();
        }
    }

    static final class Sessions implements SessionStore {
        private final Map<String, Session> byHash = new HashMap<>();
        private final Users users;

        Sessions(Users users) {
            this.users = users;
        }

        int size() {
            return byHash.size();
        }

        @Override
        public void create(String tokenHash, UUID userId, Instant now, Instant expiresAt) {
            String username = users.findById(userId).orElseThrow().username();
            byHash.put(tokenHash, new Session(userId, username, now, now, expiresAt));
        }

        @Override
        public Optional<Session> find(String tokenHash) {
            return Optional.ofNullable(byHash.get(tokenHash));
        }

        @Override
        public void touch(String tokenHash, Instant now, Instant expiresAt) {
            Session s = byHash.get(tokenHash);
            byHash.put(tokenHash, new Session(s.userId(), s.username(), s.createdAt(), now, expiresAt));
        }

        @Override
        public void delete(String tokenHash) {
            byHash.remove(tokenHash);
        }

        @Override
        public void deleteAllExcept(UUID userId, String keepTokenHash) {
            new ArrayList<>(byHash.keySet()).stream()
                    .filter(h -> byHash.get(h).userId().equals(userId) && !h.equals(keepTokenHash)).forEach(byHash::remove);
        }

        @Override
        public int purgeExpired(Instant now) {
            List<String> expired = byHash.entrySet().stream().filter(e -> !e.getValue().expiresAt().isAfter(now)).map(Map.Entry::getKey).toList();
            expired.forEach(byHash::remove);
            return expired.size();
        }
    }
}
