package com.techeazy.notification.clientapi;

import com.techeazy.notification.billing.application.AccountManagement;
import com.techeazy.notification.billing.application.CreditService;
import com.techeazy.notification.billing.application.InvoiceCsv;
import com.techeazy.notification.billing.application.InvoiceService;
import com.techeazy.notification.billing.application.PlanCatalog;
import com.techeazy.notification.billing.application.UsageService;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingNotFoundException;
import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceStatus;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.clientapi.BillingViews.AccountView;
import com.techeazy.notification.clientapi.BillingViews.InvoiceSummaryView;
import com.techeazy.notification.clientapi.BillingViews.InvoiceView;
import com.techeazy.notification.clientapi.BillingViews.LedgerPageView;
import com.techeazy.notification.clientapi.BillingViews.UsageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A client's own billing information: plan and how it pays, usage so far with its cost, issued invoices and, for
 * prepaid accounts, the credit ledger. Draft invoices are never visible to clients, and an invoice of another client
 * looks like it does not exist.
 */
@RestController
@RequestMapping("/v1/billing")
@Tag(name = "Billing")
public class BillingController {

    private static final Set<InvoiceStatus> VISIBLE_TO_CLIENTS = Set.of(InvoiceStatus.ISSUED, InvoiceStatus.PAID, InvoiceStatus.VOID);

    private final AccountManagement accounts;
    private final PlanCatalog plans;
    private final UsageService usage;
    private final InvoiceService invoices;
    private final CreditService credits;
    private final Clock clock;

    public BillingController(AccountManagement accounts, PlanCatalog plans, UsageService usage, InvoiceService invoices,
                             CreditService credits, Clock clock) {
        this.accounts = accounts;
        this.plans = plans;
        this.usage = usage;
        this.invoices = invoices;
        this.credits = credits;
        this.clock = clock;
    }

    @Operation(summary = "Your plan, how you pay, credit balance (prepaid) or spend cap (postpaid)",
            description = "404 when the client has no billing account, which means it is not billed.")
    @GetMapping("/account")
    public AccountView account(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        BillingAccount account = accounts.get(client.id());
        Plan plan = plans.get(account.planId());
        return BillingViews.account(account, plan);
    }

    @Operation(summary = "Usage and cost of a month, month=YYYY-MM (default: the current month, which is an estimate)")
    @GetMapping("/usage")
    public UsageView usage(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                           @RequestParam(required = false) String month) {
        BillingPeriod period = month == null ? BillingPeriod.current(clock) : BillingPeriod.parse(month);
        return BillingViews.usage(usage.statement(client.id(), period));
    }

    @Operation(summary = "Your invoices, newest first (issued, paid or void)")
    @GetMapping("/invoices")
    public List<InvoiceSummaryView> invoices(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client) {
        return invoices.forClient(client.id(), VISIBLE_TO_CLIENTS).stream().map(BillingViews::summary).toList();
    }

    @Operation(summary = "One invoice with its lines and payments")
    @GetMapping("/invoices/{id}")
    public InvoiceView invoice(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client, @PathVariable UUID id) {
        return BillingViews.invoice(ownInvoice(client, id));
    }

    @Operation(summary = "Download an invoice as CSV")
    @GetMapping(value = "/invoices/{id}/export", produces = "text/csv")
    public ResponseEntity<byte[]> export(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client, @PathVariable UUID id) {
        Invoice invoice = ownInvoice(client, id);
        String filename = (invoice.number() == null ? invoice.id().toString() : invoice.number()) + ".csv";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(InvoiceCsv.render(invoice).getBytes(StandardCharsets.UTF_8));
    }

    @Operation(summary = "Credit ledger of a prepaid account: top-ups, reservations and returned credit, newest first")
    @GetMapping("/ledger")
    public LedgerPageView ledger(@RequestAttribute(ClientAuthFilter.CLIENT_ATTRIBUTE) AuthenticatedClient client,
                                 @RequestParam(defaultValue = "0") int page,
                                 @RequestParam(defaultValue = "50") int size) {
        BillingAccount account = accounts.get(client.id());
        String currency = plans.get(account.planId()).currency();
        return BillingViews.ledger(credits.ledger(client.id(), page, size), currency);
    }

    private Invoice ownInvoice(AuthenticatedClient client, UUID id) {
        Invoice invoice = invoices.get(id);
        boolean visible = invoice.clientId().equals(client.id()) && VISIBLE_TO_CLIENTS.contains(invoice.status());
        if (!visible) {
            throw new BillingNotFoundException("Invoice");
        }
        return invoice;
    }
}
