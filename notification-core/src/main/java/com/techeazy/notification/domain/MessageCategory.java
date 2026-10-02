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

import java.time.Duration;

/**
 * What a message is for, which decides how it is delivered (ADR-033). A one-time password is the only priority
 * category: it travels on its own broker topic with its own consumers, may use the rate-limit tokens kept in reserve,
 * is swept first, and is never sent after its validity runs out. The priority follows from the category, so a
 * client cannot jump the queue with ordinary traffic.
 */
public enum MessageCategory {
    OTP(true),
    TRANSACTIONAL(false),
    PROMOTIONAL(false);

    public static final MessageCategory DEFAULT = TRANSACTIONAL;
    public static final Duration DEFAULT_OTP_VALIDITY = Duration.ofMinutes(5);
    public static final Duration MINIMUM_OTP_VALIDITY = Duration.ofMinutes(1);
    public static final Duration MAXIMUM_OTP_VALIDITY = Duration.ofMinutes(15);

    private final boolean priority;

    MessageCategory(boolean priority) {
        this.priority = priority;
    }

    public boolean isPriority() {
        return priority;
    }
}
