/*
 * Copyright 2026 Vasantha Kumar
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author Vasantha Kumar <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.billing.domain;

import com.techeazy.notification.domain.Channel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns one period of metered usage into invoice lines under a plan. Pure: the same plan and usage always give the
 * same lines, which is what makes regenerating a draft safe.
 *
 * <p>Per channel, the free allowance is applied first and only the remainder is charged. Channels with no usage are
 * omitted. The platform fee, when set, is one extra line. Amounts are rounded to two decimals per line.
 *
 * <p>When the tenant has an OTP price for a channel, its one-time passwords get a line of their own at that price
 * (ADR-034). The channel's free allowance is shared: it covers ordinary messages first, and what is left covers
 * one-time passwords. Without an OTP price, one-time passwords are ordinary messages of the channel.
 */
public final class InvoiceCalculator {

    /** Usage without one-time passwords, or for a tenant without OTP prices. */
    public List<InvoiceLine> linesFor(Plan plan, Map<Channel, Long> sentByChannel) {
        Map<Channel, SentCount> sent = new EnumMap<>(Channel.class);
        sentByChannel.forEach((channel, count) -> sent.put(channel, SentCount.ordinary(count)));
        return linesFor(plan, OtpPrices.NONE, sent);
    }

    public List<InvoiceLine> linesFor(Plan plan, OtpPrices otpPrices, Map<Channel, SentCount> sentByChannel) {
        otpPrices.currency().filter(currency -> !currency.equals(plan.currency())).ifPresent(currency -> {
            throw new InvalidBillingStateException("OTP prices are in " + currency + " but the plan is in "
                    + plan.currency());
        });
        List<InvoiceLine> lines = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            SentCount sent = sentByChannel.getOrDefault(channel, SentCount.ordinary(0));
            if (sent.total() > 0) {
                addUsageLines(lines, plan.rateFor(channel), otpPrices.priceFor(channel), channel, sent);
            }
        }
        if (plan.platformFee().isPositive()) {
            lines.add(new InvoiceLine(InvoiceLineKind.PLATFORM_FEE, null, "Platform fee", 1,
                    plan.platformFee(), plan.platformFee().rounded()));
        }
        return lines;
    }

    private static void addUsageLines(List<InvoiceLine> lines, ChannelRate rate, Optional<Money> otpPrice,
                                      Channel channel, SentCount sent) {
        if (otpPrice.isEmpty() || sent.oneTimePasswords() == 0) {
            long included = Math.min(sent.total(), rate.freeAllowance());
            lines.add(usageLine(InvoiceLineKind.USAGE, channel, "messages", sent.total(), included, rate.unitPrice()));
            return;
        }
        long includedOrdinary = Math.min(sent.ordinary(), rate.freeAllowance());
        long includedOtp = Math.min(sent.oneTimePasswords(), rate.freeAllowance() - includedOrdinary);
        if (sent.ordinary() > 0) {
            lines.add(usageLine(InvoiceLineKind.USAGE, channel, "messages", sent.ordinary(), includedOrdinary,
                    rate.unitPrice()));
        }
        lines.add(usageLine(InvoiceLineKind.OTP_USAGE, channel, "one-time passwords", sent.oneTimePasswords(),
                includedOtp, otpPrice.get()));
    }

    private static InvoiceLine usageLine(InvoiceLineKind kind, Channel channel, String what, long sent, long included,
                                         Money unitPrice) {
        long billable = sent - included;
        String description = String.format("%s %s: %d sent, %d included free", channel, what, sent, included);
        return new InvoiceLine(kind, channel, description, billable, unitPrice, unitPrice.times(billable).rounded());
    }
}
