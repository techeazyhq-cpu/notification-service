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

import org.springframework.jdbc.core.simple.JdbcClient;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL adapter for {@link AdminUserStore}; every counter update is a single atomic statement. */
class JdbcAdminUserStore implements AdminUserStore {

    private static final String COLUMNS = "id, username, password_hash, password_changed_at, totp_secret, totp_enabled, totp_last_step, locked_until";

    private final JdbcClient jdbc;

    JdbcAdminUserStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long count() {
        return jdbc.sql("SELECT count(*) FROM admin_user").query(Long.class).single();
    }

    @Override
    public Optional<AdminUser> findByUsername(String username) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM admin_user WHERE username = :username")
                .param("username", username).query(JdbcAdminUserStore::map).optional();
    }

    @Override
    public Optional<AdminUser> findById(UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM admin_user WHERE id = :id")
                .param("id", id).query(JdbcAdminUserStore::map).optional();
    }

    @Override
    public void insert(AdminUser user, Instant now) {
        jdbc.sql("INSERT INTO admin_user (id, username, password_hash, created_at) VALUES (:id, :username, :hash, :now)")
                .param("id", user.id()).param("username", user.username()).param("hash", user.passwordHash())
                .param("now", Timestamp.from(now)).update();
    }

    @Override
    public void updatePassword(UUID id, String passwordHash, Instant now) {
        jdbc.sql("UPDATE admin_user SET password_hash = :hash, password_changed_at = :now WHERE id = :id")
                .param("hash", passwordHash).param("now", Timestamp.from(now)).param("id", id).update();
    }

    @Override
    public void saveTotp(UUID id, String encryptedSecret, boolean enabled) {
        jdbc.sql("UPDATE admin_user SET totp_secret = :secret, totp_enabled = :enabled WHERE id = :id")
                .param("secret", encryptedSecret).param("enabled", enabled).param("id", id).update();
    }

    @Override
    public boolean advanceTotpStep(UUID id, long step) {
        return jdbc.sql("UPDATE admin_user SET totp_last_step = :step WHERE id = :id AND totp_last_step < :step")
                .param("step", step).param("id", id).update() == 1;
    }

    @Override
    public void recordFailure(UUID id, int maxAttempts, Instant lockedUntilWhenExceeded) {
        jdbc.sql("""
                UPDATE admin_user SET
                    locked_until    = CASE WHEN failed_attempts + 1 >= :max THEN :lockUntil ELSE locked_until END,
                    failed_attempts = CASE WHEN failed_attempts + 1 >= :max THEN 0 ELSE failed_attempts + 1 END
                WHERE id = :id
                """).param("max", maxAttempts).param("lockUntil", Timestamp.from(lockedUntilWhenExceeded)).param("id", id).update();
    }

    @Override
    public void clearFailures(UUID id) {
        jdbc.sql("UPDATE admin_user SET failed_attempts = 0, locked_until = NULL WHERE id = :id").param("id", id).update();
    }

    @Override
    public void replaceRecoveryCodes(UUID id, List<String> codeHashes) {
        jdbc.sql("DELETE FROM admin_recovery_code WHERE user_id = :id").param("id", id).update();
        for (String hash : codeHashes) {
            jdbc.sql("INSERT INTO admin_recovery_code (user_id, code_hash) VALUES (:id, :hash)").param("id", id).param("hash", hash).update();
        }
    }

    @Override
    public boolean consumeRecoveryCode(UUID id, String codeHash, Instant now) {
        return jdbc.sql("UPDATE admin_recovery_code SET used_at = :now WHERE user_id = :id AND code_hash = :hash AND used_at IS NULL")
                .param("now", Timestamp.from(now)).param("id", id).param("hash", codeHash).update() == 1;
    }

    @Override
    public int remainingRecoveryCodes(UUID id) {
        return jdbc.sql("SELECT count(*) FROM admin_recovery_code WHERE user_id = :id AND used_at IS NULL")
                .param("id", id).query(Integer.class).single();
    }

    private static AdminUser map(ResultSet rs, int row) throws SQLException {
        Timestamp changed = rs.getTimestamp("password_changed_at");
        Timestamp locked = rs.getTimestamp("locked_until");
        return new AdminUser(rs.getObject("id", UUID.class), rs.getString("username"), rs.getString("password_hash"),
                changed == null ? null : changed.toInstant(), rs.getString("totp_secret"), rs.getBoolean("totp_enabled"),
                rs.getLong("totp_last_step"), locked == null ? null : locked.toInstant());
    }
}
