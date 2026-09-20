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

import com.techeazy.notification.billing.application.port.AccountLookup;
import com.techeazy.notification.billing.application.port.PlanLookup;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class CachedLookupsTest {

    @Test
    void anAccountIsLoadedOncePerClientIncludingTheAbsenceOfAnAccount() {
        AtomicInteger loads = new AtomicInteger();
        AccountLookup cached = CachedLookups.accounts(id -> {
            loads.incrementAndGet();
            return Optional.empty();
        }, Duration.ofMinutes(1));
        UUID client = UUID.randomUUID();

        assertThat(cached.findByClientId(client)).isEmpty();
        assertThat(cached.findByClientId(client)).isEmpty();
        cached.findByClientId(UUID.randomUUID());

        assertThat(loads).hasValue(2);
    }

    @Test
    void aPlanIsLoadedOncePerId() {
        AtomicInteger loads = new AtomicInteger();
        PlanLookup cached = CachedLookups.plans(id -> {
            loads.incrementAndGet();
            return Optional.empty();
        }, Duration.ofMinutes(1));
        UUID plan = UUID.randomUUID();

        cached.findById(plan);
        cached.findById(plan);

        assertThat(loads).hasValue(1);
    }

    @Test
    void entriesExpireSoAChangeIsSeenAfterTheLifetime() throws InterruptedException {
        AtomicInteger loads = new AtomicInteger();
        AccountLookup cached = CachedLookups.accounts(id -> {
            loads.incrementAndGet();
            return Optional.empty();
        }, Duration.ofMillis(50));
        UUID client = UUID.randomUUID();
        cached.findByClientId(client);

        Thread.sleep(120);
        cached.findByClientId(client);

        assertThat(loads).hasValue(2);
    }
}
