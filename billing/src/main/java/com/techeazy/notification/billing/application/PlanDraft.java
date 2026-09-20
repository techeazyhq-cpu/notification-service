package com.techeazy.notification.billing.application;

import com.techeazy.notification.billing.domain.ChannelRate;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.domain.Channel;

import java.math.BigDecimal;
import java.util.Map;

public record PlanDraft(String name, String currency, Money platformFee, BigDecimal taxRate,
                        Map<Channel, ChannelRate> rates, boolean active) {}
