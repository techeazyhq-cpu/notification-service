package com.techeazy.notification.billing.domain;

import java.math.BigDecimal;
import java.util.List;

/** Subtotal, tax and total of a set of invoice lines. The single place these are calculated, for invoices and estimates alike. */
public record InvoiceTotals(Money subtotal, Money tax, Money total) {

    public static InvoiceTotals of(String currency, List<InvoiceLine> lines, BigDecimal taxRate) {
        Money subtotal = lines.stream().map(InvoiceLine::amount).reduce(Money.zero(currency), Money::plus);
        Money tax = subtotal.percent(taxRate).rounded();
        return new InvoiceTotals(subtotal, tax, subtotal.plus(tax));
    }
}
