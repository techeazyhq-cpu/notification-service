package com.techeazy.notification.billing.domain;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/** One calendar month in UTC, the unit invoices are issued for. */
public record BillingPeriod(YearMonth month) {

    public static BillingPeriod parse(String text) {
        try {
            return new BillingPeriod(YearMonth.parse(text));
        } catch (DateTimeParseException | NullPointerException e) {
            throw new InvalidBillingDataException("Month must look like 2026-08");
        }
    }

    public static BillingPeriod of(LocalDate firstDay) {
        return new BillingPeriod(YearMonth.from(firstDay));
    }

    public static BillingPeriod current(Clock clock) {
        return new BillingPeriod(YearMonth.now(clock.withZone(ZoneOffset.UTC)));
    }

    public static BillingPeriod previous(Clock clock) {
        return new BillingPeriod(current(clock).month.minusMonths(1));
    }

    public LocalDate firstDay() {
        return month.atDay(1);
    }

    public Instant start() {
        return firstDay().atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant endExclusive() {
        return month.plusMonths(1).atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public boolean isClosedAt(Instant now) {
        return !now.isBefore(endExclusive());
    }

    public String label() {
        return month.toString();
    }
}
