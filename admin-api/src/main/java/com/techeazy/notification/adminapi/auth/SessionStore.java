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
import java.util.Optional;
import java.util.UUID;

/** Persistence port for login sessions; only a hash of the bearer token is ever stored. */
interface SessionStore {

    record Session(UUID userId, String username, Instant createdAt, Instant lastSeenAt, Instant expiresAt) {}

    void create(String tokenHash, UUID userId, Instant now, Instant expiresAt);

    Optional<Session> find(String tokenHash);

    void touch(String tokenHash, Instant now, Instant expiresAt);

    void delete(String tokenHash);

    void deleteAllExcept(UUID userId, String keepTokenHash);

    int purgeExpired(Instant now);
}
