package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.LedgerEntry;

import java.util.List;

public record LedgerPage(List<LedgerEntry> items, long total, int page, int size) {}
