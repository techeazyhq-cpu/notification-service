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
package com.techeazy.notification.port;

import java.time.Instant;
import java.util.UUID;

/** Remembers the nonces of signed requests until their timestamp leaves the acceptance window (ADR-036). */
public interface NonceStore {

    /**
     * Records that {@code clientId} used {@code nonce}, unless it already did and that use is still live at {@code now}.
     * A use stays live up to and including its {@code expiresAt}, because the signature gate still accepts a request
     * signed exactly one window before {@code now}.
     *
     * @return true when this is the nonce's first live use; false when it is a replay
     */
    boolean claim(UUID clientId, String nonce, Instant now, Instant expiresAt);

    /** Forgets at most {@code limit} nonces that expired before {@code now}, returning how many went. */
    int purgeExpired(Instant now, int limit);
}
