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

package com.techeazy.notification.domain;

import java.util.Set;

/** Lifecycle of a single recipient message. */
public enum MessageStatus {
    /** Persisted, not yet confirmed as published to Pulsar (outbox state). */
    PENDING,
    /** Published to Pulsar, waiting for a dispatcher. */
    QUEUED,
    /** Claimed by a dispatcher worker. */
    PROCESSING,
    /** Last attempt failed with a transient error; a retry is scheduled. */
    RETRYING,
    SENT,
    FAILED;

    public static final Set<MessageStatus> IN_FLIGHT = Set.of(PENDING, QUEUED, PROCESSING, RETRYING);
    public static final Set<MessageStatus> CLAIMABLE = Set.of(PENDING, QUEUED, RETRYING);

    public boolean isTerminal() {
        return this == SENT || this == FAILED;
    }
}
