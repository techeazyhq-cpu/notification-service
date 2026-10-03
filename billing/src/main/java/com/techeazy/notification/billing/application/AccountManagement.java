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
import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.application.port.OtpPriceRepository;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.InvalidBillingStateException;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.OtpPrices;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Puts a client on a plan, in postpaid or prepaid mode. A prepaid account with credit or open holds cannot change
 * mode or currency, because that would strand or misprice money that is already in the ledger.
 */
public class AccountManagement {

    private final AccountRepository accounts;
    private final PlanRepository plans;
    private final CreditStore credits;
    private final OtpPriceRepository otpPrices;
    private final Transactions transactions;

    public AccountManagement(AccountRepository accounts, PlanRepository plans, CreditStore credits,
                             OtpPriceRepository otpPrices, Transactions transactions) {
        this.accounts = accounts;
        this.plans = plans;
        this.credits = credits;
        this.otpPrices = otpPrices;
        this.transactions = transactions;
    }

    /** The tenant's own OTP prices (ADR-034); {@link OtpPrices#NONE} when every channel uses the plan's price. */
    public OtpPrices otpPrices(UUID clientId) {
        get(clientId);
        return otpPrices.findByClientId(clientId);
    }

    /**
     * Replaces the tenant's OTP prices, which are in its plan's currency; a channel left out charges one-time passwords
     * at the plan's price again, and an empty map removes them all (ADR-034).
     */
    public OtpPrices setOtpPrices(UUID clientId, Map<Channel, BigDecimal> unitPrices) {
        BillingAccount account = get(clientId);
        Plan plan = plans.findById(account.planId()).orElseThrow(() -> new BillingNotFoundException("Plan"));
        Map<Channel, Money> prices = new EnumMap<>(Channel.class);
        unitPrices.forEach((channel, price) -> prices.put(channel, new Money(price, plan.currency())));
        OtpPrices updated = new OtpPrices(prices);
        return transactions.inTransaction(() -> {
            otpPrices.replace(clientId, updated);
            return updated;
        });
    }

    public BillingAccount assign(AccountAssignment assignment) {
        Plan plan = plans.findById(assignment.planId()).orElseThrow(() -> new BillingNotFoundException("Plan"));
        if (!plan.active()) {
            throw new InvalidBillingStateException("Plan '" + plan.name() + "' is not active");
        }
        requireCapCurrency(assignment, plan);
        requireOtpPriceCurrency(assignment, plan);
        BillingAccount account = accounts.findByClientId(assignment.clientId())
                .map(existing -> reassign(existing, assignment, plan))
                .orElseGet(() -> newAccount(assignment, plan));
        return accounts.save(account);
    }

    public BillingAccount get(UUID clientId) {
        return accounts.findByClientId(clientId).orElseThrow(() -> new BillingNotFoundException("Billing account"));
    }

    public List<BillingAccount> list() {
        return accounts.findAll();
    }

    private BillingAccount newAccount(AccountAssignment a, Plan plan) {
        return new BillingAccount(a.clientId(), a.planId(), a.mode(), a.monthlySpendCap(), Money.zero(plan.currency()),
                a.status(), a.billingEmail());
    }

    private BillingAccount reassign(BillingAccount existing, AccountAssignment a, Plan plan) {
        boolean holdsMoney = existing.creditBalance().isPositive() || credits.hasOpenHolds(existing.clientId());
        if (holdsMoney && existing.mode() != a.mode()) {
            throw new InvalidBillingStateException("Billing mode cannot change while the account has credit or open holds");
        }
        if (holdsMoney && !existing.creditBalance().currency().equals(plan.currency())) {
            throw new InvalidBillingStateException("Currency cannot change while the account has credit or open holds");
        }
        Money balance = existing.creditBalance().isZero() ? Money.zero(plan.currency()) : existing.creditBalance();
        return new BillingAccount(existing.clientId(), a.planId(), a.mode(), a.monthlySpendCap(), balance, a.status(),
                a.billingEmail());
    }

    /** OTP prices are in the plan's currency, so the tenant cannot move to a plan in another while it has any. */
    private void requireOtpPriceCurrency(AccountAssignment a, Plan plan) {
        otpPrices.findByClientId(a.clientId()).currency()
                .filter(currency -> !currency.equals(plan.currency()))
                .ifPresent(currency -> {
                    throw new InvalidBillingStateException("The account has OTP prices in " + currency
                            + "; remove them before moving it to a plan in " + plan.currency());
                });
    }

    private static void requireCapCurrency(AccountAssignment a, Plan plan) {
        if (a.monthlySpendCap() != null && !a.monthlySpendCap().currency().equals(plan.currency())) {
            throw new InvalidBillingDataException("The spend cap must be in the plan currency " + plan.currency());
        }
    }
}
