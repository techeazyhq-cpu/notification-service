package com.techeazy.notification.billing.domain;

import com.techeazy.notification.domain.Channel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanTest {

    private static Plan plan(BigDecimal tax, Money fee, Map<Channel, ChannelRate> rates) {
        return new Plan(UUID.randomUUID(), "standard", "USD", fee, tax, rates, true);
    }

    @Test
    void anUnpricedChannelIsFree() {
        Plan plan = plan(BigDecimal.ZERO, Money.zero("USD"), Map.of(Channel.SMS, new ChannelRate(Money.of("0.05", "USD"), 100)));

        assertThat(plan.rateFor(Channel.SMS).unitPrice()).isEqualTo(Money.of("0.05", "USD"));
        assertThat(plan.rateFor(Channel.EMAIL).unitPrice().isZero()).isTrue();
        assertThat(plan.rateFor(Channel.EMAIL).freeAllowance()).isZero();
    }

    @Test
    void rejectsTaxOutsideZeroToOne() {
        assertThatThrownBy(() -> plan(new BigDecimal("1.5"), Money.zero("USD"), Map.of())).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> plan(new BigDecimal("-0.1"), Money.zero("USD"), Map.of())).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void rejectsRatesAndFeesInAnotherCurrency() {
        Map<Channel, ChannelRate> euroRates = Map.of(Channel.SMS, new ChannelRate(Money.of("0.05", "EUR"), 0));

        assertThatThrownBy(() -> plan(BigDecimal.ZERO, Money.zero("USD"), euroRates)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> plan(BigDecimal.ZERO, Money.of("5", "EUR"), Map.of())).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void rejectsNegativePricesAndAllowances() {
        assertThatThrownBy(() -> new ChannelRate(Money.of("-1", "USD"), 0)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> new ChannelRate(Money.of("1", "USD"), -1)).isInstanceOf(InvalidBillingDataException.class);
        assertThatThrownBy(() -> plan(BigDecimal.ZERO, Money.of("-5", "USD"), Map.of())).isInstanceOf(InvalidBillingDataException.class);
    }

    @Test
    void ratesCannotBeChangedAfterConstruction() {
        Plan plan = plan(BigDecimal.ZERO, Money.zero("USD"), Map.of(Channel.SMS, ChannelRate.free("USD")));

        assertThatThrownBy(() -> plan.rates().put(Channel.PUSH, ChannelRate.free("USD"))).isInstanceOf(UnsupportedOperationException.class);
    }
}
