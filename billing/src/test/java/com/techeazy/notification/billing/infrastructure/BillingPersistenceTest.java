package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.application.Admission;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.application.CreditService;
import com.techeazy.notification.billing.application.HoldSettlement;
import com.techeazy.notification.billing.application.InvoiceService;
import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.application.port.CreditStore.CompletedHold;
import com.techeazy.notification.billing.application.port.InvoiceRepository;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.CreditHold;
import com.techeazy.notification.billing.domain.HoldScope;
import com.techeazy.notification.billing.domain.HoldStatus;
import com.techeazy.notification.billing.domain.InsufficientCreditException;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceAlreadyExistsException;
import com.techeazy.notification.billing.domain.InvoiceCalculator;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceLineKind;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.LedgerEntry;
import com.techeazy.notification.billing.domain.LedgerEntryType;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Payment;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.Database;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.DirectoryResourceAccessor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Adapter tests against a real PostgreSQL with the real Liquibase changelog; skipped automatically without Docker. */
@Testcontainers(disabledWithoutDocker = true)
class BillingPersistenceTest {

    private static final String USD = "USD";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-15T10:00:00Z"), ZoneOffset.UTC);

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static DriverManagerDataSource dataSource;
    private static JdbcClient jdbc;
    private static PlanRepository plans;
    private static AccountRepository accounts;
    private static InvoiceRepository invoices;
    private static CreditStore credits;
    private static UsageReader usage;
    private static Transactions transactions;
    private static TransactionTemplate template;

    @BeforeAll
    static void migrateAndWire() throws Exception {
        dataSource = new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Connection connection = dataSource.getConnection()) {
            Database database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection));
            DirectoryResourceAccessor changelogs = new DirectoryResourceAccessor(Path.of("../db-migration/src/main/resources"));
            try (Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.yaml", changelogs, database)) {
                liquibase.update(new Contexts(), new LabelExpression());
            }
        }
        jdbc = JdbcClient.create(dataSource);
        plans = new JdbcPlanRepository(jdbc);
        accounts = new JdbcAccountRepository(jdbc);
        invoices = new JdbcInvoiceRepository(jdbc);
        credits = new JdbcCreditStore(jdbc);
        usage = new JdbcUsageReader(jdbc);
        template = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions = new SpringTransactions(template);
    }

    private static Money usd(String amount) {
        return Money.of(amount, USD);
    }

    private static UUID newClient() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO client (id, name, api_key_hash, api_key_prefix, status, allowed_channels, created_at, updated_at)
                VALUES (:id, :name, :hash, 'ntf_test', 'ACTIVE', 'SMS,EMAIL', now(), now())""")
                .param("id", id).param("name", "client-" + id).param("hash", id.toString().replace("-", "") + id.toString().replace("-", ""))
                .update();
        return id;
    }

    private static Plan newPlan(String unitPrice, long allowance) {
        return plans.save(new Plan(UUID.randomUUID(), "plan-" + UUID.randomUUID(), USD, Money.zero(USD), new BigDecimal("0.10"),
                Map.of(Channel.SMS, new ChannelRate(usd(unitPrice), allowance)), true));
    }

    private static BillingAccount newAccount(UUID client, Plan plan, BillingMode mode) {
        return accounts.save(new BillingAccount(client, plan.id(), mode, null, Money.zero(USD), AccountStatus.ACTIVE, null));
    }

    private static UUID newRequest(UUID client) {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO notification_request (id, client_id, kind, channel, total, created_at) VALUES (:id, :client, 'BULK', 'SMS', 0, now())")
                .param("id", id).param("client", client).update();
        return id;
    }

    private static UUID newMessage(UUID request, UUID client, Channel channel, String status, String sentAt) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO notification_message (id, request_id, client_id, channel, recipient, status, attempts, created_at, updated_at, sent_at)
                VALUES (:id, :request, :client, :channel, '+14155550100', :status, 0, now(), now(), :sentAt)""")
                .param("id", id).param("request", request).param("client", client).param("channel", channel.name())
                .param("status", status).param("sentAt", sentAt == null ? null : Timestamp.from(Instant.parse(sentAt)))
                .update();
        return id;
    }

    @Test
    void plansRoundTripWithTheirRatesAndUpdatesReplaceThem() {
        Plan created = plans.save(new Plan(UUID.randomUUID(), "roundtrip-" + UUID.randomUUID(), USD, usd("49.99"), new BigDecimal("0.1800"),
                Map.of(Channel.SMS, new ChannelRate(usd("0.0035"), 1000), Channel.PUSH, new ChannelRate(usd("0.001"), 0)), true));

        Plan loaded = plans.findById(created.id()).orElseThrow();
        assertThat(loaded).isEqualTo(created);
        assertThat(plans.findByName(created.name())).contains(created);
        assertThat(plans.findAll()).contains(created);

        Plan updated = plans.save(new Plan(created.id(), created.name(), USD, usd("10"), new BigDecimal("0.05"),
                Map.of(Channel.EMAIL, new ChannelRate(usd("0.02"), 5)), false));
        assertThat(plans.findById(created.id()).orElseThrow()).isEqualTo(updated);
        assertThat(plans.findById(created.id()).orElseThrow().rates()).containsOnlyKeys(Channel.EMAIL);
    }

    @Test
    void savingAnAccountNeverOverwritesTheCreditBalance() {
        UUID client = newClient();
        Plan plan = newPlan("0.05", 0);
        newAccount(client, plan, BillingMode.PREPAID);
        credits.credit(client, usd("50"));

        BillingAccount saved = accounts.save(new BillingAccount(client, plan.id(), BillingMode.PREPAID, null, Money.zero(USD),
                AccountStatus.SUSPENDED, "billing@acme.test"));

        assertThat(saved.creditBalance()).isEqualTo(usd("50"));
        assertThat(saved.status()).isEqualTo(AccountStatus.SUSPENDED);
        assertThat(saved.billingEmail()).isEqualTo("billing@acme.test");
        assertThat(accounts.existsWithPlan(plan.id())).isTrue();
        assertThat(accounts.findAll()).extracting(BillingAccount::clientId).contains(client);
    }

    @Test
    void anAccountForAnUnknownClientIsRejected() {
        Plan plan = newPlan("0.05", 0);

        assertThatThrownBy(() -> accounts.save(new BillingAccount(UUID.randomUUID(), plan.id(), BillingMode.POSTPAID, null,
                Money.zero(USD), AccountStatus.ACTIVE, null))).isInstanceOf(BillingNotFoundException.class);
    }

    @Test
    void theBalanceNeverGoesNegativeWhenManyReservationsRaceForIt() throws Exception {
        UUID client = newClient();
        newAccount(client, newPlan("1", 0), BillingMode.PREPAID);
        credits.credit(client, usd("100"));
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Callable<Boolean>> attempts = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            attempts.add(() -> credits.debitIfSufficient(client, usd("10")));
        }

        long successes = 0;
        try {
            for (Future<Boolean> attempt : pool.invokeAll(attempts)) {
                successes += attempt.get(60, TimeUnit.SECONDS) ? 1 : 0;
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(successes).isEqualTo(10);
        assertThat(credits.balance(client)).isEqualTo(usd("0"));
    }

    @Test
    void ledgerEntriesAreIdempotentPerClientTypeAndReferenceAndPaged() {
        UUID client = newClient();
        newAccount(client, newPlan("1", 0), BillingMode.PREPAID);
        Instant base = Instant.parse("2026-09-01T10:00:00Z");

        boolean first = credits.appendLedger(LedgerEntry.of(client, LedgerEntryType.TOP_UP, usd("10"), "pay-1", "Payment", base));
        boolean duplicate = credits.appendLedger(LedgerEntry.of(client, LedgerEntryType.TOP_UP, usd("10"), "pay-1", "Payment", base));
        credits.appendLedger(LedgerEntry.of(client, LedgerEntryType.TOP_UP, usd("5"), "pay-2", "Payment", base.plusSeconds(60)));
        credits.appendLedger(LedgerEntry.of(client, LedgerEntryType.HOLD, usd("-3"), "hold-1", "Reserved", base.plusSeconds(120)));

        assertThat(first).isTrue();
        assertThat(duplicate).isFalse();
        assertThat(credits.ledgerSize(client)).isEqualTo(3);
        assertThat(credits.ledger(client, 2, 0)).extracting(LedgerEntry::reference).containsExactly("hold-1", "pay-2");
        assertThat(credits.ledger(client, 2, 2)).extracting(LedgerEntry::reference).containsExactly("pay-1");
        assertThat(credits.ledger(client, 10, 0).get(0).amount()).isEqualTo(usd("-3"));
    }

    @Test
    void invoicesRoundTripWithLinesAndPaymentsAndAreListedAndSearched() {
        UUID client = newClient();
        Plan plan = newPlan("0.05", 0);
        List<InvoiceLine> lines = List.of(
                new InvoiceLine(InvoiceLineKind.USAGE, Channel.SMS, "SMS messages", 500, usd("0.05"), usd("25.00")),
                new InvoiceLine(InvoiceLineKind.PLATFORM_FEE, null, "Platform fee", 1, usd("10"), usd("10.00")));
        Invoice invoice = Invoice.draft(client, plan.id(), BillingPeriod.parse("2026-08"), USD, new BigDecimal("0.10"), lines, CLOCK.instant());
        invoices.save(invoice);
        invoice.issue(invoices.nextNumber(CLOCK.instant()), CLOCK.instant(), 30);
        invoice.recordPayment(new Payment(UUID.randomUUID(), usd("15.00"), "bank transfer", "wire-1", CLOCK.instant()), CLOCK.instant());
        invoices.save(invoice);

        Invoice loaded = invoices.findById(invoice.id()).orElseThrow();

        assertThat(loaded.state()).isEqualTo(invoice.state());
        assertThat(loaded.total()).isEqualTo(usd("38.50"));
        assertThat(loaded.outstanding()).isEqualTo(usd("23.50"));
        assertThat(loaded.number()).startsWith("INV-2026-");
        assertThat(invoices.findByIdForUpdate(invoice.id())).isPresent();
        assertThat(invoices.findCurrent(client, BillingPeriod.parse("2026-08"))).isPresent();
        assertThat(invoices.findByClient(client, Set.of(InvoiceStatus.ISSUED))).hasSize(1);
        assertThat(invoices.findByClient(client, Set.of(InvoiceStatus.DRAFT, InvoiceStatus.PAID))).isEmpty();
        assertThat(invoices.search(InvoiceStatus.ISSUED, client, 10)).hasSize(1);
        assertThat(invoices.search(null, client, 10)).hasSize(1);
        assertThat(invoices.search(null, null, 500)).extracting(Invoice::id).contains(invoice.id());
    }

    @Test
    void invoiceNumbersAreSequentialAndFormatted() {
        String first = invoices.nextNumber(CLOCK.instant());
        String second = invoices.nextNumber(CLOCK.instant());

        assertThat(first).matches("INV-2026-\\d{6}");
        assertThat(Long.parseLong(second.substring(9))).isEqualTo(Long.parseLong(first.substring(9)) + 1);
    }

    @Test
    void onlyOneLiveInvoiceExistsPerClientAndPeriodButAVoidedOneFreesThePeriod() {
        UUID client = newClient();
        Plan plan = newPlan("0.05", 0);
        BillingPeriod period = BillingPeriod.parse("2026-07");
        Invoice first = Invoice.draft(client, plan.id(), period, USD, BigDecimal.ZERO, List.of(), CLOCK.instant());
        invoices.save(first);

        Invoice clash = Invoice.draft(client, plan.id(), period, USD, BigDecimal.ZERO, List.of(), CLOCK.instant());
        assertThatThrownBy(() -> invoices.save(clash)).isInstanceOf(InvoiceAlreadyExistsException.class);

        first.voidInvoice("mistake", CLOCK.instant());
        invoices.save(first);
        Invoice replacement = Invoice.draft(client, plan.id(), period, USD, BigDecimal.ZERO, List.of(), CLOCK.instant());
        invoices.save(replacement);
        assertThat(invoices.findCurrent(client, period).orElseThrow().id()).isEqualTo(replacement.id());
    }

    @Test
    void usageCountsOnlySentMessagesInsideTheWindowPerChannelAndClient() {
        UUID client = newClient();
        UUID other = newClient();
        UUID request = newRequest(client);
        newMessage(request, client, Channel.SMS, "SENT", "2026-08-10T10:00:00Z");
        newMessage(request, client, Channel.SMS, "SENT", "2026-08-31T23:59:59Z");
        newMessage(request, client, Channel.EMAIL, "SENT", "2026-08-15T10:00:00Z");
        newMessage(request, client, Channel.SMS, "SENT", "2026-09-01T00:00:00Z");
        newMessage(request, client, Channel.SMS, "SENT", "2026-07-31T23:59:59Z");
        newMessage(request, client, Channel.SMS, "FAILED", "2026-08-12T10:00:00Z");
        newMessage(newRequest(other), other, Channel.SMS, "SENT", "2026-08-10T10:00:00Z");

        Map<Channel, Long> august = usage.sentByChannel(client, Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"));

        assertThat(august).containsOnly(Map.entry(Channel.SMS, 2L), Map.entry(Channel.EMAIL, 1L));
    }

    @Test
    void onlyHoldsWhoseMessagesAllFinishedAreReturnedForSettlementWithTheirSentCount() {
        UUID client = newClient();
        newAccount(client, newPlan("0.10", 0), BillingMode.PREPAID);
        UUID finished = newRequest(client);
        newMessage(finished, client, Channel.SMS, "SENT", "2026-09-10T10:00:00Z");
        newMessage(finished, client, Channel.SMS, "SENT", "2026-09-10T10:00:01Z");
        newMessage(finished, client, Channel.SMS, "FAILED", null);
        UUID running = newRequest(client);
        newMessage(running, client, Channel.SMS, "SENT", "2026-09-10T10:00:00Z");
        newMessage(running, client, Channel.SMS, "QUEUED", null);
        UUID retried = newMessage(finished, client, Channel.SMS, "SENT", "2026-09-11T10:00:00Z");
        CreditHold requestHold = CreditHold.place(client, HoldScope.REQUEST, finished, Channel.SMS, 3, usd("0.10"), CLOCK.instant());
        CreditHold runningHold = CreditHold.place(client, HoldScope.REQUEST, running, Channel.SMS, 2, usd("0.10"), CLOCK.instant());
        CreditHold messageHold = CreditHold.place(client, HoldScope.MESSAGE, retried, Channel.SMS, 1, usd("0.10"), CLOCK.instant());

        List<CompletedHold> completed = transactions.inTransaction(() -> {
            credits.saveHold(requestHold);
            credits.saveHold(runningHold);
            credits.saveHold(messageHold);
            return credits.lockCompletedHolds(50);
        });

        assertThat(completed).extracting(c -> c.hold().id()).containsExactlyInAnyOrder(requestHold.id(), messageHold.id());
        assertThat(completed).filteredOn(c -> c.hold().id().equals(requestHold.id())).singleElement()
                .satisfies(c -> assertThat(c.sentCount()).isEqualTo(3));
        assertThat(completed).filteredOn(c -> c.hold().id().equals(messageHold.id())).singleElement()
                .satisfies(c -> assertThat(c.sentCount()).isEqualTo(1));
        assertThat(credits.hasHeldFor(HoldScope.REQUEST, running)).isTrue();
        assertThat(credits.hasOpenHolds(client)).isTrue();
    }

    @Test
    void anAdmissionIsUndoneWhenTheEnclosingTransactionRollsBack() {
        UUID client = newClient();
        Plan plan = newPlan("0.10", 0);
        newAccount(client, plan, BillingMode.PREPAID);
        credits.credit(client, usd("10"));
        AdmissionControl admission = new AdmissionControl(accounts, plans, credits, usage, new InvoiceCalculator(), CLOCK);
        UUID request = UUID.randomUUID();

        assertThatThrownBy(() -> template.executeWithoutResult(status -> {
            admission.admit(new Admission(client, Channel.SMS, 50, HoldScope.REQUEST, request));
            throw new IllegalStateException("storing the request failed");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(credits.balance(client)).isEqualTo(usd("10"));
        assertThat(credits.hasHeldFor(HoldScope.REQUEST, request)).isFalse();
        assertThat(credits.ledgerSize(client)).isZero();
    }

    @Test
    void aPrepaidClientPaysOnlyForWhatWasSentEndToEnd() {
        UUID client = newClient();
        newAccount(client, newPlan("0.10", 0), BillingMode.PREPAID);
        CreditService creditService = new CreditService(accounts, plans, credits, transactions, CLOCK);
        AdmissionControl admission = new AdmissionControl(accounts, plans, credits, usage, new InvoiceCalculator(), CLOCK);
        HoldSettlement settlement = new HoldSettlement(credits, transactions, CLOCK);
        creditService.topUp(client, usd("10"), "pay-1", "Card payment");
        UUID request = newRequest(client);

        transactions.inTransaction(() -> admission.admit(new Admission(client, Channel.SMS, 50, HoldScope.REQUEST, request)));
        assertThat(credits.balance(client)).isEqualTo(usd("5"));
        assertThatThrownBy(() -> transactions.inTransaction(() -> admission.admit(new Admission(client, Channel.SMS, 100, HoldScope.REQUEST, UUID.randomUUID()))))
                .isInstanceOf(InsufficientCreditException.class);

        for (int i = 0; i < 30; i++) {
            newMessage(request, client, Channel.SMS, "SENT", "2026-09-10T10:00:00Z");
        }
        for (int i = 0; i < 20; i++) {
            newMessage(request, client, Channel.SMS, "FAILED", null);
        }
        int settled = settlement.settleCompleted(50);

        assertThat(settled).isEqualTo(1);
        assertThat(credits.balance(client)).isEqualTo(usd("7"));
        List<LedgerEntry> ledger = credits.ledger(client, 50, 0);
        assertThat(ledger.stream().map(LedgerEntry::amount).reduce(Money.zero(USD), Money::plus)).isEqualTo(credits.balance(client));
        assertThat(ledger).extracting(LedgerEntry::type).containsExactlyInAnyOrder(LedgerEntryType.TOP_UP, LedgerEntryType.HOLD, LedgerEntryType.SETTLEMENT);
        assertThat(credits.hasHeldFor(HoldScope.REQUEST, request)).isFalse();
        assertThat(settlement.settleCompleted(50)).isZero();
        assertThat(HoldStatus.valueOf(jdbc.sql("SELECT status FROM credit_hold WHERE reference_id = :r").param("r", request)
                .query(String.class).single())).isEqualTo(HoldStatus.SETTLED);
    }

    @Test
    void postpaidInvoicingUsesRealUsageAndIsIdempotent() {
        UUID client = newClient();
        Plan plan = newPlan("0.05", 100);
        newAccount(client, plan, BillingMode.POSTPAID);
        UUID request = newRequest(client);
        for (int i = 0; i < 150; i++) {
            newMessage(request, client, Channel.SMS, "SENT", "2026-08-20T10:00:00Z");
        }
        newMessage(request, client, Channel.SMS, "FAILED", "2026-08-20T10:00:00Z");
        Clock september = Clock.fixed(Instant.parse("2026-09-03T02:00:00Z"), ZoneOffset.UTC);
        InvoiceService service = new InvoiceService(invoices, accounts, plans, usage, new InvoiceCalculator(), transactions, september, 30);
        BillingPeriod august = BillingPeriod.parse("2026-08");

        Invoice first = service.generateFor(client, august);
        Invoice second = service.generateFor(client, august);
        Invoice issued = service.issue(first.id());

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(first.lines()).singleElement().satisfies(line -> assertThat(line.quantity()).isEqualTo(50));
        assertThat(first.subtotal()).isEqualTo(usd("2.50"));
        assertThat(issued.status()).isEqualTo(InvoiceStatus.ISSUED);
        assertThat(invoices.findById(first.id()).orElseThrow().number()).isEqualTo(issued.number());
    }
}
