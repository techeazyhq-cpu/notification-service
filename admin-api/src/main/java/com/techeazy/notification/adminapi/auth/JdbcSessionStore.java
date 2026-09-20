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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL adapter for {@link SessionStore}. */
class JdbcSessionStore implements SessionStore {

    private final JdbcClient jdbc;

    JdbcSessionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(String tokenHash, UUID userId, Instant now, Instant expiresAt) {
        jdbc.sql("INSERT INTO admin_session (token_hash, user_id, created_at, last_seen_at, expires_at) VALUES (:hash, :user, :now, :now, :expires)")
                .param("hash", tokenHash).param("user", userId).param("now", Timestamp.from(now)).param("expires", Timestamp.from(expiresAt)).update();
    }

    @Override
    public Optional<Session> find(String tokenHash) {
        return jdbc.sql("""
                SELECT s.user_id, u.username, s.created_at, s.last_seen_at, s.expires_at
                FROM admin_session s JOIN admin_user u ON u.id = s.user_id
                WHERE s.token_hash = :hash
                """).param("hash", tokenHash).query((rs, row) -> new Session(rs.getObject("user_id", UUID.class), rs.getString("username"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant(), rs.getTimestamp("expires_at").toInstant())).optional();
    }

    @Override
    public void touch(String tokenHash, Instant now, Instant expiresAt) {
        jdbc.sql("UPDATE admin_session SET last_seen_at = :now, expires_at = :expires WHERE token_hash = :hash")
                .param("now", Timestamp.from(now)).param("expires", Timestamp.from(expiresAt)).param("hash", tokenHash).update();
    }

    @Override
    public void delete(String tokenHash) {
        jdbc.sql("DELETE FROM admin_session WHERE token_hash = :hash").param("hash", tokenHash).update();
    }

    @Override
    public void deleteAllExcept(UUID userId, String keepTokenHash) {
        jdbc.sql("DELETE FROM admin_session WHERE user_id = :user AND token_hash <> :keep")
                .param("user", userId).param("keep", keepTokenHash == null ? "" : keepTokenHash).update();
    }

    @Override
    public int purgeExpired(Instant now) {
        return jdbc.sql("DELETE FROM admin_session WHERE expires_at <= :now").param("now", Timestamp.from(now)).update();
    }
}
