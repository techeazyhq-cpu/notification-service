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
package com.techeazy.notification.persistence;

import com.techeazy.notification.port.NonceStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

/**
 * PostgreSQL adapter for {@link NonceStore}. The primary key makes a claim atomic across every client-api instance,
 * and unlike a cache the table cannot evict a live nonce under memory pressure. An expired row with the same nonce
 * is taken over in the same statement, so a purge that has not run yet never refuses a valid request.
 */
@Repository
public class JdbcNonceStore implements NonceStore {

    private static final String CLAIM = """
            INSERT INTO api_request_nonce (client_id, nonce, expires_at) VALUES (:client, :nonce, :expires)
            ON CONFLICT (client_id, nonce) DO UPDATE SET expires_at = EXCLUDED.expires_at
            WHERE api_request_nonce.expires_at <= :now""";

    private static final String PURGE = """
            DELETE FROM api_request_nonce
            WHERE ctid IN (SELECT ctid FROM api_request_nonce WHERE expires_at <= :now LIMIT :limit)""";

    private final JdbcClient jdbc;

    public JdbcNonceStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean claim(UUID clientId, String nonce, Instant now, Instant expiresAt) {
        return jdbc.sql(CLAIM).param("client", clientId).param("nonce", nonce)
                .param("expires", Timestamp.from(expiresAt)).param("now", Timestamp.from(now)).update() == 1;
    }

    @Override
    public int purgeExpired(Instant now, int limit) {
        return jdbc.sql(PURGE).param("now", Timestamp.from(now)).param("limit", limit).update();
    }
}
