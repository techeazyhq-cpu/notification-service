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

package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.InvoiceCalculator;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceTotals;
import com.techeazy.notification.billing.domain.Plan;

import java.time.Clock;
import java.util.List;
import java.util.UUID;

/**
 * What a client has used so far in a period and what that costs under its plan, using the same calculation as invoices.
 * For prepaid accounts the figures are informational: the actual charge is taken from credit per message.
 */
public class UsageService {

    private final AccountRepository accounts;
    private final PlanRepository plans;
    private final UsageReader usage;
    private final InvoiceCalculator calculator;
    private final Clock clock;

    public UsageService(AccountRepository accounts, PlanRepository plans, UsageReader usage, InvoiceCalculator calculator,
                        Clock clock) {
        this.accounts = accounts;
        this.plans = plans;
        this.usage = usage;
        this.calculator = calculator;
        this.clock = clock;
    }

    public UsageStatement statement(UUID clientId, BillingPeriod period) {
        BillingAccount account = accounts.findByClientId(clientId)
                .orElseThrow(() -> new BillingNotFoundException("Billing account"));
        Plan plan = plans.findById(account.planId()).orElseThrow(() -> new BillingNotFoundException("Plan"));
        List<InvoiceLine> lines = calculator.linesFor(plan, usage.sentByChannel(clientId, period.start(), period.endExclusive()));
        return new UsageStatement(period, plan.currency(), lines, InvoiceTotals.of(plan.currency(), lines, plan.taxRate()),
                !period.isClosedAt(clock.instant()));
    }
}
