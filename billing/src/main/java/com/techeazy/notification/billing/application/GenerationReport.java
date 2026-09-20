package com.techeazy.notification.billing.application;

import java.util.List;

/** Outcome of generating invoices for every postpaid account: newly created, already existing, and failures per client. */
public record GenerationReport(int created, int existing, List<String> failures) {}
