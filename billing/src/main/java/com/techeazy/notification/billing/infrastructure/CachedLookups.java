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

package com.techeazy.notification.billing.infrastructure;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.techeazy.notification.billing.application.port.AccountLookup;
import com.techeazy.notification.billing.application.port.PlanLookup;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.Plan;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Short-lived caches for the two reads every accepted request makes before it may charge or count anything: the
 * client's billing account and its plan. Money is never cached: balances and holds are always read and changed by
 * atomic statements. What can be late is a change to the account itself (suspension, plan, mode, spend cap) or to a
 * plan's prices, by at most the configured number of seconds on each instance, including "this client has no account".
 */
final class CachedLookups {

    private CachedLookups() {
    }

    static AccountLookup accounts(AccountLookup source, Duration ttl) {
        Cache<UUID, Optional<BillingAccount>> cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(50_000).build();
        return clientId -> cache.get(clientId, source::findByClientId);
    }

    static PlanLookup plans(PlanLookup source, Duration ttl) {
        Cache<UUID, Optional<Plan>> cache = Caffeine.newBuilder().expireAfterWrite(ttl).maximumSize(5_000).build();
        return id -> cache.get(id, source::findById);
    }
}
