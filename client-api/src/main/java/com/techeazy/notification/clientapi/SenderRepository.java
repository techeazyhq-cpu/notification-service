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

package com.techeazy.notification.clientapi;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** PostgreSQL storage for {@link SenderAddress}; every state change is one conditional statement. */
@Repository
public class SenderRepository {

    private static final String CLIENT = "client";
    private static final String EMAIL = "email";

    private static final String COLUMNS = "id, client_id, email, display_name, status, is_default, created_at, verified_at, verification_sent_at";
    private static final String SELECT_SENDER = "SELECT " + COLUMNS + " FROM client_sender";

    private final JdbcClient jdbc;

    public SenderRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SenderAddress sender, String tokenHash, Instant tokenExpiresAt) {
        jdbc.sql("""
                INSERT INTO client_sender (id, client_id, email, display_name, status, is_default, token_hash, token_expires_at,
                                           verification_sent_at, created_at)
                VALUES (:id, :client, :email, :name, 'PENDING', FALSE, :hash, :expires, :now, :now)
                """).param("id", sender.id()).param(CLIENT, sender.clientId()).param(EMAIL, sender.email())
                .param("name", sender.displayName()).param("hash", tokenHash).param("expires", Timestamp.from(tokenExpiresAt))
                .param("now", Timestamp.from(sender.createdAt())).update();
    }

    public List<SenderAddress> findByClient(UUID clientId) {
        return jdbc.sql(SELECT_SENDER + " WHERE client_id = :client ORDER BY created_at")
                .param(CLIENT, clientId).query(SenderRepository::map).list();
    }

    public Optional<SenderAddress> find(UUID clientId, UUID id) {
        return jdbc.sql(SELECT_SENDER + " WHERE client_id = :client AND id = :id")
                .param(CLIENT, clientId).param("id", id).query(SenderRepository::map).optional();
    }

    public Optional<SenderAddress> findVerifiedByEmail(UUID clientId, String email) {
        return jdbc.sql(SELECT_SENDER + " WHERE client_id = :client AND lower(email) = lower(:email) AND status = 'VERIFIED'")
                .param(CLIENT, clientId).param(EMAIL, email).query(SenderRepository::map).optional();
    }

    public Optional<SenderAddress> findVerifiedDefault(UUID clientId) {
        return jdbc.sql(SELECT_SENDER + " WHERE client_id = :client AND is_default AND status = 'VERIFIED'")
                .param(CLIENT, clientId).query(SenderRepository::map).optional();
    }

    public int countByClient(UUID clientId) {
        return jdbc.sql("SELECT count(*) FROM client_sender WHERE client_id = :client").param(CLIENT, clientId).query(Integer.class).single();
    }

    public boolean emailExists(UUID clientId, String email) {
        return jdbc.sql("SELECT count(*) FROM client_sender WHERE client_id = :client AND lower(email) = lower(:email)")
                .param(CLIENT, clientId).param(EMAIL, email).query(Integer.class).single() > 0;
    }

    public void replaceToken(UUID id, String tokenHash, Instant expiresAt, Instant sentAt) {
        jdbc.sql("UPDATE client_sender SET token_hash = :hash, token_expires_at = :expires, verification_sent_at = :sent WHERE id = :id AND status = 'PENDING'")
                .param("hash", tokenHash).param("expires", Timestamp.from(expiresAt)).param("sent", Timestamp.from(sentAt)).param("id", id).update();
    }

    /** Marks the sender that owns an unexpired token as verified and invalidates the token; empty when the token is unknown, used or expired. */
    public Optional<SenderAddress> verifyByToken(String tokenHash, Instant now) {
        return jdbc.sql("""
                UPDATE client_sender SET status = 'VERIFIED', verified_at = :now, token_hash = NULL, token_expires_at = NULL
                WHERE token_hash = :hash AND token_expires_at > :now AND status = 'PENDING'
                RETURNING %s
                """.formatted(COLUMNS)).param("hash", tokenHash).param("now", Timestamp.from(now)).query(SenderRepository::map).optional();
    }

    public void clearDefault(UUID clientId) {
        jdbc.sql("UPDATE client_sender SET is_default = FALSE WHERE client_id = :client AND is_default").param(CLIENT, clientId).update();
    }

    public boolean markDefault(UUID clientId, UUID id) {
        return jdbc.sql("UPDATE client_sender SET is_default = TRUE WHERE client_id = :client AND id = :id AND status = 'VERIFIED'")
                .param(CLIENT, clientId).param("id", id).update() == 1;
    }

    public boolean delete(UUID clientId, UUID id) {
        return jdbc.sql("DELETE FROM client_sender WHERE client_id = :client AND id = :id").param(CLIENT, clientId).param("id", id).update() == 1;
    }

    private static SenderAddress map(ResultSet rs, int row) throws SQLException {
        Timestamp verified = rs.getTimestamp("verified_at");
        Timestamp sent = rs.getTimestamp("verification_sent_at");
        return new SenderAddress(rs.getObject("id", UUID.class), rs.getObject("client_id", UUID.class), rs.getString(EMAIL),
                rs.getString("display_name"), SenderAddress.Status.valueOf(rs.getString("status")), rs.getBoolean("is_default"),
                rs.getTimestamp("created_at").toInstant(), verified == null ? null : verified.toInstant(), sent == null ? null : sent.toInstant());
    }
}
