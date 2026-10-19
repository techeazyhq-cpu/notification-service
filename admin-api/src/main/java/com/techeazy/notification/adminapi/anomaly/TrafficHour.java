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

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * What one tenant sent on one channel in one hour, as the anomaly detection of ADR-039 sees it. It holds counts and
 * destinations only, never an address.
 *
 * @param clientId       the tenant
 * @param channel        the channel's name
 * @param hour           the start of the hour, in UTC
 * @param messages       messages accepted in the hour
 * @param recipients     distinct recipients
 * @param newRecipients  distinct recipients the tenant had not sent to in the look-back period before the hour
 * @param failed         messages that have failed so far
 * @param largestRequest the most recipients in one request accepted in the hour
 * @param destinations   messages per calling code (SMS, WhatsApp) or per e-mail domain; small domains are merged
 * @param errorCodes     failed messages per error code
 */
record TrafficHour(UUID clientId, String channel, Instant hour, int messages, int recipients, int newRecipients,
                   int failed, int largestRequest, Map<String, Integer> destinations, Map<String, Integer> errorCodes) {
}
