package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.Invoice;
import com.techeazy.notification.billing.domain.InvoiceLine;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** Renders an invoice as CSV: a header block, one row per line, then the totals. */
public final class InvoiceCsv {

    private static final String NEWLINE = "\r\n";

    private InvoiceCsv() {}

    public static String render(Invoice invoice) {
        List<String> rows = new ArrayList<>();
        rows.add(row("Invoice", invoice.number() == null ? "(draft)" : invoice.number()));
        rows.add(row("Client", invoice.clientId().toString()));
        rows.add(row("Period", invoice.period().label()));
        rows.add(row("Status", invoice.status().name()));
        rows.add(row("Currency", invoice.currency()));
        rows.add(row("Issued", invoice.issuedAt() == null ? "" : invoice.issuedAt().toString()));
        rows.add(row("Due", invoice.dueAt() == null ? "" : invoice.dueAt().toString()));
        rows.add("");
        rows.add(row("Description", "Channel", "Quantity", "Unit price", "Amount"));
        for (InvoiceLine line : invoice.lines()) {
            rows.add(row(line.description(), line.channel() == null ? "" : line.channel().name(),
                    Long.toString(line.quantity()), line.unitPrice().unitFormatted(),
                    line.amount().formatted()));
        }
        rows.add(row("Subtotal", "", "", "", invoice.subtotal().formatted()));
        rows.add(row("Tax " + invoice.taxRate().movePointRight(2).stripTrailingZeros().toPlainString() + "%", "", "", "",
                invoice.taxAmount().formatted()));
        rows.add(row("Total", "", "", "", invoice.total().formatted()));
        rows.add(row("Paid", "", "", "", invoice.paid().formatted()));
        rows.add(row("Outstanding", "", "", "", invoice.outstanding().formatted()));
        return String.join(NEWLINE, rows) + NEWLINE;
    }

    private static String row(String... cells) {
        return java.util.Arrays.stream(cells).map(InvoiceCsv::cell).collect(Collectors.joining(","));
    }

    private static String cell(String value) {
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
