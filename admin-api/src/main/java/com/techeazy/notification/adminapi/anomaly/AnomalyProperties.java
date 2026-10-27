/*
 * Copyright 2026 Vasantha Kumar Kesavan
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
 * @author Vasantha Kumar Kesavan <vasantha.kumar@hotmail.com>
 */

package com.techeazy.notification.adminapi.anomaly;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.HashMap;
import java.util.Map;

/** Traffic anomaly detection settings under {@code anomaly.*} (ADR-039). Off unless switched on in configuration. */
@ConfigurationProperties(prefix = "anomaly")
public class AnomalyProperties {

    private final Scoring scoring = new Scoring();
    private final ReportingDatabase reportingDatabase = new ReportingDatabase();
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

    public Scoring getScoring() { return scoring; }

    public ReportingDatabase getReportingDatabase() { return reportingDatabase; }

    /**
     * The database the job reads tenants' messages and requests from (ADR-050). Unset, it reads through the service's
     * own connection. Set, it logs in as a read-only role, on the primary or on a read replica.
     */
    public static class ReportingDatabase {
        private String url;
        private String username;
        private String password;
        private int maximumPoolSize = 2;

        /** Whether a reporting user is set; only then does the job get a connection of its own. */
        public boolean isConfigured() { return username != null && !username.isBlank(); }

        /** The JDBC URL; a read replica's, or the primary's. */
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        /** The read-only role; blank to read through the service's own connection. */
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        /** How many connections the job may hold; one run reads one query at a time. */
        public int getMaximumPoolSize() { return maximumPoolSize; }
        public void setMaximumPoolSize(int maximumPoolSize) { this.maximumPoolSize = maximumPoolSize; }
    }

    /** How an hour is judged against the tenant's history. Tuned by how often administrators mark false alarms. */
    public static class Scoring {
        private double warningScore = 4;
        private double highScore = 8;
        private int baselineWeeks = 8;
        private int coldStartDays = 14;
        private double minSpreadRatio = 0.1;
        private int messagesFloor = 100;
        private int newRecipientsFloor = 50;
        private int largestRequestFloor = 1000;
        private double failureRateFloor = 0.2;
        private int failureRateMinMessages = 50;
        private int newDestinationMinMessages = 10;
        private final Map<String, Integer> coldStartMessagesPerHour = new HashMap<>(
                Map.of("EMAIL", 5000, "SMS", 1000, "WHATSAPP", 1000, "PUSH", 10000));

        /** The score from which an anomaly is recorded as a warning. */
        public double getWarningScore() { return warningScore; }
        public void setWarningScore(double warningScore) { this.warningScore = warningScore; }

        /** The score from which an anomaly is high severity and alerts. */
        public double getHighScore() { return highScore; }
        public void setHighScore(double highScore) { this.highScore = highScore; }

        /** How many earlier weeks of the same hour make the baseline. */
        public int getBaselineWeeks() { return baselineWeeks; }
        public void setBaselineWeeks(int baselineWeeks) { this.baselineWeeks = baselineWeeks; }

        /** How much history a tenant needs before it is judged against itself rather than against fixed limits. */
        public int getColdStartDays() { return coldStartDays; }
        public void setColdStartDays(int coldStartDays) { this.coldStartDays = coldStartDays; }

        /** The smallest spread assumed, as a share of the median, so a perfectly steady tenant is not flagged for noise. */
        public double getMinSpreadRatio() { return minSpreadRatio; }
        public void setMinSpreadRatio(double minSpreadRatio) { this.minSpreadRatio = minSpreadRatio; }

        /** Fewer messages than this in an hour are never an anomaly, however unusual for the tenant. */
        public int getMessagesFloor() { return messagesFloor; }
        public void setMessagesFloor(int messagesFloor) { this.messagesFloor = messagesFloor; }

        /** Fewer new recipients than this in an hour are never an anomaly. */
        public int getNewRecipientsFloor() { return newRecipientsFloor; }
        public void setNewRecipientsFloor(int newRecipientsFloor) { this.newRecipientsFloor = newRecipientsFloor; }

        /** Requests smaller than this are never an anomaly. */
        public int getLargestRequestFloor() { return largestRequestFloor; }
        public void setLargestRequestFloor(int largestRequestFloor) { this.largestRequestFloor = largestRequestFloor; }

        /** A failure rate below this is never an anomaly. */
        public double getFailureRateFloor() { return failureRateFloor; }
        public void setFailureRateFloor(double failureRateFloor) { this.failureRateFloor = failureRateFloor; }

        /** Hours with fewer messages than this have too noisy a failure rate to judge. */
        public int getFailureRateMinMessages() { return failureRateMinMessages; }
        public void setFailureRateMinMessages(int failureRateMinMessages) { this.failureRateMinMessages = failureRateMinMessages; }

        /** Fewer messages than this to new destinations in an hour are not flagged. */
        public int getNewDestinationMinMessages() { return newDestinationMinMessages; }
        public void setNewDestinationMinMessages(int newDestinationMinMessages) { this.newDestinationMinMessages = newDestinationMinMessages; }

        /** Per channel, the hourly messages from which a tenant still in its cold start is flagged as a warning. */
        public Map<String, Integer> getColdStartMessagesPerHour() { return coldStartMessagesPerHour; }
    }
}
