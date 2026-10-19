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

package com.techeazy.notification.adminapi.anomaly;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Traffic anomaly detection settings under {@code anomaly.*} (ADR-039). Off unless switched on in configuration. */
@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {

    private boolean enabled;
    private String aggregationCron = "0 5 * * * *";
    private int recomputeHours = 3;
    private int newRecipientLookbackDays = 30;
    private int backfillDays = 56;
    private int retentionDays = 400;
    private int emailDomainMinRecipients = 5;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    /** When the hourly aggregation runs, in UTC; a few minutes past the hour, so the hour has closed. */
    public String getAggregationCron() { return aggregationCron; }
    public void setAggregationCron(String aggregationCron) { this.aggregationCron = aggregationCron; }

    /** How many closed hours each run measures again, so messages that fail after a retry are counted. */
    public int getRecomputeHours() { return recomputeHours; }
    public void setRecomputeHours(int recomputeHours) { this.recomputeHours = recomputeHours; }

    /** How long a recipient must have gone without a message from the tenant to count as new. */
    public int getNewRecipientLookbackDays() { return newRecipientLookbackDays; }
    public void setNewRecipientLookbackDays(int newRecipientLookbackDays) { this.newRecipientLookbackDays = newRecipientLookbackDays; }

    /** How many days of history the first run measures when there is none yet, so baselines exist from the start. */
    public int getBackfillDays() { return backfillDays; }
    public void setBackfillDays(int backfillDays) { this.backfillDays = backfillDays; }

    /** How long hourly measurements are kept. */
    public int getRetentionDays() { return retentionDays; }
    public void setRetentionDays(int retentionDays) { this.retentionDays = retentionDays; }

    /** E-mail domains with fewer distinct recipients in an hour are recorded as {@code other}, so none points to a person. */
    public int getEmailDomainMinRecipients() { return emailDomainMinRecipients; }
    public void setEmailDomainMinRecipients(int emailDomainMinRecipients) { this.emailDomainMinRecipients = emailDomainMinRecipients; }
}
