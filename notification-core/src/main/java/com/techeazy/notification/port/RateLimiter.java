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

/** Distributed token-bucket limiter. */
public interface RateLimiter {

    /** @param waitMillis when denied, how long until enough tokens exist (a hint, not a reservation) */
    record Decision(boolean allowed, long waitMillis) {
        public static final Decision GRANTED = new Decision(true, 0);
    }

    Decision tryAcquire(String key, double ratePerSecond, int burst);
}
