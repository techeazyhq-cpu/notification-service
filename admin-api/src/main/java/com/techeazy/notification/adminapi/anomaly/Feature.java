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

package com.techeazy.notification.adminapi.anomaly;

import java.util.EnumSet;
import java.util.Set;

/** What about an hour of traffic is scored against the tenant's own history (ADR-039). */
enum Feature {
    /** Messages accepted: a loop, a leaked key. */
    MESSAGES,
    /** Recipients the tenant had not sent to recently: fan-out, SMS pumping, bought lists. */
    NEW_RECIPIENTS,
    /** The biggest single request: a runaway bulk send. */
    LARGEST_REQUEST,
    /** The share of messages that failed: bad lists, number ranges providers refuse. */
    FAILURE_RATE,
    /** Calling codes the tenant has not sent to in its baseline period: the clearest sign of SMS pumping. */
    NEW_DESTINATION;

    /** Features that are final as soon as the hour closes. */
    static final Set<Feature> IMMEDIATE = EnumSet.of(MESSAGES, NEW_RECIPIENTS, LARGEST_REQUEST, NEW_DESTINATION);

    /** Features that keep changing while retries finish, so they are scored once the hour's messages have settled. */
    static final Set<Feature> SETTLED = EnumSet.of(FAILURE_RATE);
}
