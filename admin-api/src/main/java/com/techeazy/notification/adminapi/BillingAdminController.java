package com.techeazy.notification.adminapi;

import com.techeazy.notification.adminapi.BillingAdminViews.AccountView;
import com.techeazy.notification.adminapi.BillingAdminViews.GenerationView;
import com.techeazy.notification.adminapi.BillingAdminViews.InvoiceView;
import com.techeazy.notification.adminapi.BillingAdminViews.LedgerPageView;
import com.techeazy.notification.adminapi.BillingAdminViews.PlanView;
import com.techeazy.notification.billing.application.AccountAssignment;
import com.techeazy.notification.billing.application.AccountManagement;
import com.techeazy.notification.billing.application.CreditService;
import com.techeazy.notification.billing.application.GenerationReport;
import com.techeazy.notification.billing.application.InvoiceCsv;
import com.techeazy.notification.billing.application.InvoiceService;
import com.techeazy.notification.billing.application.PlanCatalog;
import com.techeazy.notification.billing.application.PlanDraft;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.domain.Client;
import com.techeazy.notification.persistence.ClientRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Administrator side of billing: price plans, which client is on which plan and how it pays, credit top-ups and
 * corrections, and the invoice lifecycle (generate, issue, record payment, void, export).
 */
@RestController
@RequestMapping("/api/admin/billing")
class BillingAdminController {

    record RateInput(@NotNull Channel channel, @NotNull BigDecimal unitPrice, long freeAllowance) {}

    record PlanInput(@NotBlank String name, @NotBlank String currency, @NotNull BigDecimal platformFee,
                     @NotNull BigDecimal taxRate, List<@Valid RateInput> rates, boolean active) {}

    record AccountInput(@NotNull UUID planId, @NotNull BillingMode mode, BigDecimal monthlySpendCap,
                        @NotNull AccountStatus status, String billingEmail) {}

    record CreditInput(@NotNull CreditKind kind, @NotNull BigDecimal amount, @NotBlank String reference, String description) {}

    enum CreditKind { TOP_UP, ADJUSTMENT }

    record GenerateInput(@NotBlank String month, UUID clientId) {}

    record PaymentInput(@NotNull BigDecimal amount, @NotBlank String method, @NotBlank String reference) {}

    record VoidInput(@NotBlank String reason) {}

    private final PlanCatalog plans;
    private final AccountManagement accounts;
    private final CreditService credits;
    private final InvoiceService invoices;
    private final ClientRepository clients;
    private final Clock clock;

    BillingAdminController(PlanCatalog plans, AccountManagement accounts, CreditService credits, InvoiceService invoices,
                           ClientRepository clients, Clock clock) {
        this.plans = plans;
        this.accounts = accounts;
        this.credits = credits;
        this.invoices = invoices;
        this.clients = clients;
        this.clock = clock;
    }

    @GetMapping("/plans")
    List<PlanView> plans() {
        return plans.list().stream().map(BillingAdminViews::plan).toList();
    }

    @PostMapping("/plans")
    @ResponseStatus(HttpStatus.CREATED)
    PlanView createPlan(@Valid @RequestBody PlanInput in) {
        return BillingAdminViews.plan(plans.create(draft(in)));
    }

    @PutMapping("/plans/{id}")
    PlanView updatePlan(@PathVariable UUID id, @Valid @RequestBody PlanInput in) {
        return BillingAdminViews.plan(plans.update(id, draft(in)));
    }

    @GetMapping("/accounts")
    List<AccountView> accounts() {
        Map<UUID, String> names = clientNames();
        Map<UUID, Plan> planById = plans.list().stream().collect(Collectors.toMap(Plan::id, Function.identity()));
        return accounts.list().stream()
                .map(a -> BillingAdminViews.account(a, names.getOrDefault(a.clientId(), "unknown"), planById.get(a.planId()))).toList();
    }

    @PutMapping("/accounts/{clientId}")
    AccountView assign(@PathVariable UUID clientId, @Valid @RequestBody AccountInput in) {
        Plan plan = plans.get(in.planId());
        Money cap = in.monthlySpendCap() == null ? null : new Money(in.monthlySpendCap(), plan.currency());
        BillingAccount saved = accounts.assign(new AccountAssignment(clientId, in.planId(), in.mode(), cap, in.status(), in.billingEmail()));
        return BillingAdminViews.account(saved, clientName(clientId), plans.get(saved.planId()));
    }

    @PostMapping("/accounts/{clientId}/credit")
    Map<String, String> credit(@PathVariable UUID clientId, @Valid @RequestBody CreditInput in) {
        Plan plan = plans.get(accounts.get(clientId).planId());
        Money amount = new Money(in.amount(), plan.currency());
        Money balance = in.kind() == CreditKind.TOP_UP
                ? credits.topUp(clientId, amount, in.reference(), in.description())
                : credits.adjust(clientId, amount, in.reference(), in.description());
        return Map.of("balance", balance.formatted(), "currency", balance.currency());
    }

    @GetMapping("/accounts/{clientId}/ledger")
    LedgerPageView ledger(@PathVariable UUID clientId, @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "50") int size) {
        return BillingAdminViews.ledger(credits.ledger(clientId, page, size));
    }

    @GetMapping("/invoices")
    List<InvoiceView> invoices(@RequestParam(required = false) InvoiceStatus status, @RequestParam(required = false) UUID clientId) {
        Map<UUID, String> names = clientNames();
        return invoices.search(status, clientId).stream()
                .map(i -> BillingAdminViews.invoice(i, names.getOrDefault(i.clientId(), "unknown"))).toList();
    }

    @GetMapping("/invoices/{id}")
    InvoiceView invoice(@PathVariable UUID id) {
        return view(invoices.get(id));
    }

    @PostMapping("/invoices/generate")
    GenerationView generate(@Valid @RequestBody GenerateInput in) {
        BillingPeriod period = BillingPeriod.parse(in.month());
        if (in.clientId() == null) {
            GenerationReport report = invoices.generateForAll(period);
            return new GenerationView(report.created(), report.existing(), report.failures());
        }
        boolean existed = invoices.search(null, in.clientId()).stream()
                .anyMatch(i -> i.period().equals(period) && i.status() != InvoiceStatus.VOID);
        invoices.generateFor(in.clientId(), period);
        return new GenerationView(existed ? 0 : 1, existed ? 1 : 0, List.of());
    }

    @PostMapping("/invoices/{id}/issue")
    InvoiceView issue(@PathVariable UUID id) {
        return view(invoices.issue(id));
    }

    @PostMapping("/invoices/{id}/payments")
    InvoiceView pay(@PathVariable UUID id, @Valid @RequestBody PaymentInput in) {
        Invoice invoice = invoices.get(id);
        return view(invoices.recordPayment(id, new Money(in.amount(), invoice.currency()), in.method(), in.reference()));
    }

    @PostMapping("/invoices/{id}/void")
    InvoiceView voidInvoice(@PathVariable UUID id, @Valid @RequestBody VoidInput in) {
        return view(invoices.voidInvoice(id, in.reason()));
    }

    @GetMapping(value = "/invoices/{id}/export", produces = "text/csv")
    ResponseEntity<byte[]> export(@PathVariable UUID id) {
        Invoice invoice = invoices.get(id);
        String filename = (invoice.number() == null ? invoice.id().toString() : invoice.number()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(InvoiceCsv.render(invoice).getBytes(StandardCharsets.UTF_8));
    }

    private PlanDraft draft(PlanInput in) {
        Map<Channel, ChannelRate> rates = new EnumMap<>(Channel.class);
        if (in.rates() != null) {
            in.rates().forEach(r -> rates.put(r.channel(), new ChannelRate(new Money(r.unitPrice(), in.currency()), r.freeAllowance())));
        }
        return new PlanDraft(in.name(), in.currency(), new Money(in.platformFee(), in.currency()), in.taxRate(), rates, in.active());
    }

    private InvoiceView view(Invoice invoice) {
        return BillingAdminViews.invoice(invoice, clientName(invoice.clientId()));
    }

    private String clientName(UUID clientId) {
        return clients.findById(clientId).map(Client::getName).orElse("unknown");
    }

    private Map<UUID, String> clientNames() {
        return clients.findAll().stream().collect(Collectors.toMap(Client::getId, Client::getName));
    }
}
