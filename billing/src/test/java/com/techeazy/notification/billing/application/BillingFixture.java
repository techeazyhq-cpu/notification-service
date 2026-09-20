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
import com.techeazy.notification.billing.application.port.InvoiceRepository;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.CreditHold;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.HoldStatus;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException;
import com.techeazy.notification.billing.domain.InvoiceCalculator;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/** In-memory implementations of every billing port plus the use cases wired to them, so use cases are tested without a database. */
final class BillingFixture {

    static final String USD = "USD";

    final MutableClock clock = new MutableClock(Instant.parse("2026-09-15T10:00:00Z"));
    final FakePlans plans = new FakePlans();
    final FakeAccounts accounts = new FakeAccounts();
    final FakeInvoices invoices = new FakeInvoices();
    final FakeCredits credits = new FakeCredits(accounts);
    final FakeUsage usage = new FakeUsage();
    final Transactions transactions = new Transactions() {
        @Override
        public <T> T inTransaction(Supplier<T> work) {
            return work.get();
        }
    };
    final InvoiceCalculator calculator = new InvoiceCalculator();

    final PlanCatalog planCatalog = new PlanCatalog(plans, accounts);
    final AccountManagement accountManagement = new AccountManagement(accounts, plans, credits);
    final CreditService creditService = new CreditService(accounts, plans, credits, transactions, clock);
    final AdmissionControl admission = new AdmissionControl(accounts, plans, credits, usage, calculator, clock);
    final HoldSettlement settlement = new HoldSettlement(credits, transactions, clock);
    final InvoiceService invoiceService = new InvoiceService(invoices, accounts, plans, usage, calculator, transactions, clock, 30);
    final UsageService usageService = new UsageService(accounts, plans, usage, calculator, clock);

    Plan savePlan(String name, String tax, String fee, Map<Channel, ChannelRate> rates) {
        return plans.save(new Plan(UUID.randomUUID(), name, USD, Money.of(fee, USD), new BigDecimal(tax), rates, true));
    }

    Plan smsPlan(String unitPrice, long allowance) {
        return savePlan("plan-" + UUID.randomUUID(), "0.10", "0",
                Map.of(Channel.SMS, new ChannelRate(Money.of(unitPrice, USD), allowance)));
    }

    BillingAccount account(UUID clientId, Plan plan, BillingMode mode, String cap) {
        return accounts.save(new BillingAccount(clientId, plan.id(), mode, cap == null ? null : Money.of(cap, USD),
                Money.zero(USD), AccountStatus.ACTIVE, null));
    }

    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(String instant) {
            this.now = Instant.parse(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static final class FakePlans implements PlanRepository {
        private final Map<UUID, Plan> byId = new LinkedHashMap<>();

        @Override
        public Plan save(Plan plan) {
            byId.put(plan.id(), plan);
            return plan;
        }

        @Override
        public Optional<Plan> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public Optional<Plan> findByName(String name) {
            return byId.values().stream().filter(p -> p.name().equals(name)).findFirst();
        }

        @Override
        public List<Plan> findAll() {
            return new ArrayList<>(byId.values());
        }

        Map<UUID, Plan> byId() {
            return byId;
        }
    }

    static final class FakeAccounts implements AccountRepository {
        private final Map<UUID, BillingAccount> byClient = new LinkedHashMap<>();

        @Override
        public BillingAccount save(BillingAccount account) {
            BillingAccount existing = byClient.get(account.clientId());
            BillingAccount stored = existing == null ? account : new BillingAccount(account.clientId(), account.planId(),
                    account.mode(), account.monthlySpendCap(), existing.creditBalance(), account.status(), account.billingEmail());
            byClient.put(stored.clientId(), stored);
            return stored;
        }

        @Override
        public Optional<BillingAccount> findByClientId(UUID clientId) {
            return Optional.ofNullable(byClient.get(clientId));
        }

        @Override
        public List<BillingAccount> findAll() {
            return new ArrayList<>(byClient.values());
        }

        @Override
        public boolean existsWithPlan(UUID planId) {
            return byClient.values().stream().anyMatch(a -> a.planId().equals(planId));
        }

        void setBalance(UUID clientId, Money balance) {
            BillingAccount a = byClient.get(clientId);
            byClient.put(clientId, new BillingAccount(a.clientId(), a.planId(), a.mode(), a.monthlySpendCap(), balance,
                    a.status(), a.billingEmail()));
        }
    }

    static final class FakeInvoices implements InvoiceRepository {
        private final Map<UUID, Invoice.State> byId = new LinkedHashMap<>();
        private int sequence;

        @Override
        public void save(Invoice invoice) {
            boolean clash = byId.values().stream().anyMatch(s -> !s.id().equals(invoice.id())
                    && s.clientId().equals(invoice.clientId()) && s.period().equals(invoice.period())
                    && s.status() != InvoiceStatus.VOID && invoice.status() != InvoiceStatus.VOID);
            if (clash) {
                throw new InvoiceAlreadyExistsException();
            }
            byId.put(invoice.id(), invoice.state());
        }

        @Override
        public Optional<Invoice> findById(UUID id) {
            return Optional.ofNullable(byId.get(id)).map(Invoice::restore);
        }

        @Override
        public Optional<Invoice> findByIdForUpdate(UUID id) {
            return findById(id);
        }

        @Override
        public Optional<Invoice> findCurrent(UUID clientId, BillingPeriod period) {
            return byId.values().stream().filter(s -> s.clientId().equals(clientId) && s.period().equals(period)
                    && s.status() != InvoiceStatus.VOID).findFirst().map(Invoice::restore);
        }

        @Override
        public List<Invoice> findByClient(UUID clientId, Set<InvoiceStatus> statuses) {
            return byId.values().stream().filter(s -> s.clientId().equals(clientId) && statuses.contains(s.status()))
                    .map(Invoice::restore).toList();
        }

        @Override
        public List<Invoice> search(InvoiceStatus status, UUID clientId, int limit) {
            return byId.values().stream()
                    .filter(s -> status == null || s.status() == status)
                    .filter(s -> clientId == null || s.clientId().equals(clientId))
                    .sorted(Comparator.comparing((Invoice.State s) -> s.period().month()).reversed())
                    .limit(limit).map(Invoice::restore).toList();
        }

        @Override
        public String nextNumber(Instant now) {
            return String.format("INV-%d-%06d", now.atZone(ZoneOffset.UTC).getYear(), ++sequence);
        }

        int count() {
            return byId.size();
        }
    }

    static final class FakeCredits implements CreditStore {
        private final FakeAccounts accounts;
        final List<LedgerEntry> ledger = new ArrayList<>();
        final Map<UUID, CreditHold> holds = new LinkedHashMap<>();
        private final Map<UUID, Long> completedSent = new HashMap<>();

        FakeCredits(FakeAccounts accounts) {
            this.accounts = accounts;
        }

        void complete(UUID referenceId, long sentCount) {
            completedSent.put(referenceId, sentCount);
        }

        @Override
        public boolean debitIfSufficient(UUID clientId, Money amount) {
            Money balance = balance(clientId);
            if (balance.compareTo(amount) < 0) {
                return false;
            }
            accounts.setBalance(clientId, balance.minus(amount));
            return true;
        }

        @Override
        public void credit(UUID clientId, Money amount) {
            accounts.setBalance(clientId, balance(clientId).plus(amount));
        }

        @Override
        public Money balance(UUID clientId) {
            return accounts.findByClientId(clientId).orElseThrow().creditBalance();
        }

        @Override
        public boolean appendLedger(LedgerEntry entry) {
            boolean duplicate = ledger.stream().anyMatch(e -> e.clientId().equals(entry.clientId())
                    && e.type() == entry.type() && e.reference().equals(entry.reference()));
            if (!duplicate) {
                ledger.add(entry);
            }
            return !duplicate;
        }

        @Override
        public List<LedgerEntry> ledger(UUID clientId, int limit, int offset) {
            return ledger.stream().filter(e -> e.clientId().equals(clientId)).skip(offset).limit(limit).toList();
        }

        @Override
        public long ledgerSize(UUID clientId) {
            return ledger.stream().filter(e -> e.clientId().equals(clientId)).count();
        }

        Money ledgerSum(UUID clientId, String currency) {
            return ledger.stream().filter(e -> e.clientId().equals(clientId)).map(LedgerEntry::amount)
                    .reduce(Money.zero(currency), Money::plus);
        }

        @Override
        public void saveHold(CreditHold hold) {
            holds.put(hold.id(), hold);
        }

        @Override
        public void updateHold(CreditHold hold) {
            holds.put(hold.id(), hold);
        }

        @Override
        public boolean hasHeldFor(HoldScope scope, UUID referenceId) {
            return holds.values().stream().anyMatch(h -> h.scope() == scope && h.referenceId().equals(referenceId)
                    && h.status() == HoldStatus.HELD);
        }

        @Override
        public boolean hasOpenHolds(UUID clientId) {
            return holds.values().stream().anyMatch(h -> h.clientId().equals(clientId) && h.status() == HoldStatus.HELD);
        }

        @Override
        public List<CompletedHold> lockCompletedHolds(int limit) {
            return holds.values().stream()
                    .filter(h -> h.status() == HoldStatus.HELD && completedSent.containsKey(h.referenceId()))
                    .limit(limit).map(h -> new CompletedHold(h, completedSent.get(h.referenceId()))).toList();
        }
    }

    static final class FakeUsage implements UsageReader {
        private record Sent(UUID clientId, Channel channel, Instant at, long count) {}

        private final List<Sent> sent = new ArrayList<>();

        void sent(UUID clientId, Channel channel, String at, long count) {
            sent.add(new Sent(clientId, channel, Instant.parse(at), count));
        }

        @Override
        public Map<Channel, Long> sentByChannel(UUID clientId, Instant fromInclusive, Instant toExclusive) {
            Map<Channel, Long> totals = new EnumMap<>(Channel.class);
            sent.stream().filter(s -> s.clientId().equals(clientId) && !s.at().isBefore(fromInclusive) && s.at().isBefore(toExclusive))
                    .forEach(s -> totals.merge(s.channel(), s.count(), Long::sum));
            return totals;
        }
    }
}
