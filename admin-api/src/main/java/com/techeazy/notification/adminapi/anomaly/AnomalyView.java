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

package com.techeazy.notification.adminapi.anomaly;

import java.time.Instant;
import java.util.UUID;

/**
 * A recorded anomaly as the admin console shows it.
 *
 * @param id             the anomaly
 * @param clientId       the tenant
 * @param clientName     the tenant's name, or {@code null} if the tenant no longer exists
 * @param channel        the channel's name
 * @param hour           the start of the hour, in UTC
 * @param feature        what stood out
 * @param method         how it was found, which says how to read {@code expected} and {@code score}
 * @param expected       the baseline median, the cold-start limit, or zero for a new destination
 * @param actual         what the hour measured
 * @param score          how far it stood out
 * @param severity       how urgent it is
 * @param detail         for a new destination, the calling codes
 * @param status         where it stands
 * @param detectedAt     when it was found
 * @param resolvedBy     the administrator who decided it, if decided
 * @param resolvedAt     when it was decided
 * @param resolutionNote why, in the administrator's words
 */
record AnomalyView(UUID id, UUID clientId, String clientName, String channel, Instant hour, Feature feature,
                   DetectionMethod method, double expected, double actual, double score, Severity severity,
                   String detail, AnomalyStatus status, Instant detectedAt, String resolvedBy,
                   Instant resolvedAt, String resolutionNote) {
}
