package com.techeazy.notification.billing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tax rates are fractions between 0 and 1 kept at four decimal places (0.1800 is 18 percent). Normalising the scale
 * makes equal rates compare equal whether they were typed as 0.18 or read back from the database as 0.1800.
 */
final class TaxRate {

    private static final int SCALE = 4;

    private TaxRate() {}

    static BigDecimal normalize(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) {
            throw new InvalidBillingDataException("Tax rate must be between 0 and 1");
        }
        return rate.setScale(SCALE, RoundingMode.HALF_UP);
    }
}
