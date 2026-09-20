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

package com.techeazy.notification.billing.infrastructure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Billing settings under {@code billing.*}. The two background jobs are off by default and switched on in the
 * dispatcher, which runs the other background work.
 */
@ConfigurationProperties(prefix = "billing")
public class BillingProperties {

    private int paymentTermsDays = 30;
    private final Settlement settlement = new Settlement();
    private final Invoicing invoicing = new Invoicing();

    public int getPaymentTermsDays() { return paymentTermsDays; }
    public void setPaymentTermsDays(int paymentTermsDays) { this.paymentTermsDays = paymentTermsDays; }
    public Settlement getSettlement() { return settlement; }
    public Invoicing getInvoicing() { return invoicing; }

    /** Settles prepaid holds once their messages have finished. */
    public static class Settlement {
        private boolean enabled;
        private long intervalMs = 10_000;
        private int batchSize = 200;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public long getIntervalMs() { return intervalMs; }
        public void setIntervalMs(long intervalMs) { this.intervalMs = intervalMs; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    }

    /** Generates the previous month's invoices for every postpaid account, and optionally issues them. */
    public static class Invoicing {
        private boolean enabled;
        private String cron = "0 0 2 1 * *";
        private boolean autoIssue;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getCron() { return cron; }
        public void setCron(String cron) { this.cron = cron; }
        public boolean isAutoIssue() { return autoIssue; }
        public void setAutoIssue(boolean autoIssue) { this.autoIssue = autoIssue; }
    }
}
