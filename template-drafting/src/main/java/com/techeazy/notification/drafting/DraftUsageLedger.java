/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.drafting;

import com.techeazy.notification.domain.Channel;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One row per draft that reached the model, with the tokens it spent. The monthly budgets are sums over it, so the
 * budget survives restarts and is shared by every admin-api and client-api instance. Never holds the brief or the
 * draft itself.
 */
public interface DraftUsageLedger {

    /** How a use of the model ended. */
    enum Outcome {
        /** A draft that met the channel's rules was returned. */
        DRAFTED,
        /** Every attempt broke the channel's rules. */
        OUTPUT_REJECTED,
        /** The model declined the brief. */
        DECLINED,
        /** The model failed part-way, after tokens had already been spent on an earlier attempt. */
        FAILED
    }

    /**
     * @param actor    the administrator's username or the client's name
     * @param clientId the client, or null for an administrator
     */
    record Usage(UUID id, Instant occurredAt, String actor, UUID clientId, Channel channel, String model,
                 long inputTokens, long outputTokens, Outcome outcome) {}

    void record(Usage usage);

    /** Tokens spent by everyone since the given instant. */
    long tokensSince(Instant since);

    /** Tokens spent by one client since the given instant. */
    long tokensSince(UUID clientId, Instant since);

    /** Tokens spent by each client since the given instant; a client that spent nothing is absent. */
    Map<UUID, Long> tokensByClientSince(Instant since);
}
