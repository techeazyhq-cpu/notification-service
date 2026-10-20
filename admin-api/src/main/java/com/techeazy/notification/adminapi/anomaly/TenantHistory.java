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
import java.util.List;
import java.util.Set;

/**
 * What a tenant's earlier traffic on one channel looked like, as far as scoring one hour needs it.
 *
 * @param firstSeen          the first hour ever measured for the tenant and channel
 * @param sameHourOfWeek     measurements of the same hour of the week in the baseline period; hours without traffic
 *                           are absent
 * @param knownDestinations  calling codes or domains used anywhere in the baseline period
 */
record TenantHistory(Instant firstSeen, List<TrafficHour> sameHourOfWeek, Set<String> knownDestinations) {
}
