package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.BillingPeriod;
import com.techeazy.notification.billing.domain.InvoiceLine;
import com.techeazy.notification.billing.domain.InvoiceTotals;

import java.util.List;

/** Usage of a period priced at the client's plan. {@code estimate} is true while the period is still running. */
public record UsageStatement(BillingPeriod period, String currency, List<InvoiceLine> lines, InvoiceTotals totals,
                             boolean estimate) {}
