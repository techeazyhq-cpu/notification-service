package com.techeazy.notification.billing.infrastructure;

import com.techeazy.notification.billing.application.GenerationReport;
import com.techeazy.notification.billing.application.HoldSettlement;
import com.techeazy.notification.billing.application.InvoiceService;
import com.techeazy.notification.billing.domain.BillingPeriod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/** Scheduled billing work. Every job is safe to run on several instances at once. */
public class BillingJobs {

    private BillingJobs() {}

    /** Settles prepaid holds whose messages have finished. Workers skip rows another worker already holds. */
    @Component
    @ConditionalOnProperty(prefix = "billing.settlement", name = "enabled", havingValue = "true")
    static class SettlementJob {

        private static final Logger log = LoggerFactory.getLogger(SettlementJob.class);

        private final HoldSettlement settlement;
        private final int batchSize;

        SettlementJob(HoldSettlement settlement, BillingProperties properties) {
            this.settlement = settlement;
            this.batchSize = properties.getSettlement().getBatchSize();
        }

        @Scheduled(fixedDelayString = "${billing.settlement.interval-ms:10000}")
        void run() {
            int settled = settlement.settleCompleted(batchSize);
            if (settled > 0) {
                log.info("Settled {} prepaid hold(s)", settled);
            }
        }
    }

    /** Creates last month's invoices. Generation is idempotent, so a repeated or concurrent run changes nothing. */
    @Component
    @ConditionalOnProperty(prefix = "billing.invoicing", name = "enabled", havingValue = "true")
    static class MonthlyInvoicingJob {

        private static final Logger log = LoggerFactory.getLogger(MonthlyInvoicingJob.class);

        private final InvoiceService invoices;
        private final BillingProperties properties;
        private final Clock clock;

        MonthlyInvoicingJob(InvoiceService invoices, BillingProperties properties, Clock clock) {
            this.invoices = invoices;
            this.properties = properties;
            this.clock = clock;
        }

        @Scheduled(cron = "${billing.invoicing.cron:0 0 2 1 * *}", zone = "UTC")
        void run() {
            BillingPeriod period = BillingPeriod.previous(clock);
            GenerationReport report = invoices.generateForAll(period);
            log.info("Invoices for {}: {} created, {} existing, {} failed", period.label(), report.created(),
                    report.existing(), report.failures().size());
            if (properties.getInvoicing().isAutoIssue()) {
                log.info("Issued {} invoice(s) for {}", invoices.issueDrafts(period), period.label());
            }
        }
    }
}
