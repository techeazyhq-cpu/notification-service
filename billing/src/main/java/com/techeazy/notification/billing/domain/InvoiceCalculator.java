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
import java.util.List;
import java.util.Map;

/**
 * Turns one period of metered usage into invoice lines under a plan. Pure: the same plan and usage always give the
 * same lines, which is what makes regenerating a draft safe.
 *
 * <p>Per channel, the free allowance is applied first and only the remainder is charged. Channels with no usage are
 * omitted. The platform fee, when set, is one extra line. Amounts are rounded to two decimals per line.
 */
public final class InvoiceCalculator {

    public List<InvoiceLine> linesFor(Plan plan, Map<Channel, Long> sentByChannel) {
        List<InvoiceLine> lines = new ArrayList<>();
        for (Channel channel : Channel.values()) {
            long sent = sentByChannel.getOrDefault(channel, 0L);
            if (sent > 0) {
                lines.add(usageLine(plan.rateFor(channel), channel, sent));
            }
        }
        if (plan.platformFee().isPositive()) {
            lines.add(new InvoiceLine(InvoiceLineKind.PLATFORM_FEE, null, "Platform fee", 1,
                    plan.platformFee(), plan.platformFee().rounded()));
        }
        return lines;
    }

    private InvoiceLine usageLine(ChannelRate rate, Channel channel, long sent) {
        long included = Math.min(sent, rate.freeAllowance());
        long billable = sent - included;
        String description = String.format("%s messages: %d sent, %d included free", channel, sent, included);
        return new InvoiceLine(InvoiceLineKind.USAGE, channel, description, billable, rate.unitPrice(),
                rate.unitPrice().times(billable).rounded());
    }
}
