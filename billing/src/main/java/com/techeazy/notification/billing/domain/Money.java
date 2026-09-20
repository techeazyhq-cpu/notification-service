package com.techeazy.notification.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.Objects;

/**
 * An amount in one currency. Amounts are kept at six decimal places, enough for unit prices such as 0.0035;
 * {@link #rounded()} applies the two-decimal rounding used on invoice lines and totals.
 */
public record Money(BigDecimal amount, String currency) implements Comparable<Money> {

    private static final int STORAGE_SCALE = 6;
    private static final int INVOICE_SCALE = 2;

    public Money {
        Objects.requireNonNull(amount, "amount");
        if (currency == null || currency.length() != 3) {
            throw new InvalidBillingDataException("Currency must be a 3-letter ISO 4217 code");
        }
        currency = currency.toUpperCase(Locale.ROOT);
        amount = amount.setScale(STORAGE_SCALE, RoundingMode.HALF_UP);
    }

    public static Money zero(String currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public static Money of(String amount, String currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    public Money times(long quantity) {
        return new Money(amount.multiply(BigDecimal.valueOf(quantity)), currency);
    }

    public Money percent(BigDecimal fraction) {
        return new Money(amount.multiply(fraction), currency);
    }

    public Money rounded() {
        return new Money(amount.setScale(INVOICE_SCALE, RoundingMode.HALF_UP), currency);
    }

    public Money min(Money other) {
        return compareTo(other) <= 0 ? this : other;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    /** The amount with two decimals, for totals and line amounts: {@code 25.00}. */
    public String formatted() {
        return amount.setScale(INVOICE_SCALE, RoundingMode.HALF_UP).toPlainString();
    }

    /** The amount for unit prices: significant decimals kept, at least two shown: {@code 0.05}, {@code 0.0035}. */
    public String unitFormatted() {
        BigDecimal stripped = amount.stripTrailingZeros();
        return (stripped.scale() < INVOICE_SCALE ? stripped.setScale(INVOICE_SCALE) : stripped).toPlainString();
    }

    public String display() {
        return formatted() + " " + currency;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new InvalidBillingDataException("Currency mismatch: " + currency + " and " + other.currency);
        }
    }
}
