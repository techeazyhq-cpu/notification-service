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

import com.techeazy.notification.billing.application.AccountManagement;
import com.techeazy.notification.billing.application.AdmissionControl;
import com.techeazy.notification.billing.application.CreditService;
import com.techeazy.notification.billing.application.HoldSettlement;
import com.techeazy.notification.billing.application.InvoiceService;
import com.techeazy.notification.billing.application.PlanCatalog;
import com.techeazy.notification.billing.application.UsageService;
import com.techeazy.notification.billing.application.port.AccountLookup;
import com.techeazy.notification.billing.application.port.AccountRepository;
import com.techeazy.notification.billing.application.port.CreditStore;
import com.techeazy.notification.billing.application.port.InvoiceRepository;
import com.techeazy.notification.billing.application.port.OtpPriceLookup;
import com.techeazy.notification.billing.application.port.OtpPriceRepository;
import com.techeazy.notification.billing.application.port.PlanLookup;
import com.techeazy.notification.billing.application.port.PlanRepository;
import com.techeazy.notification.billing.application.port.Transactions;
import com.techeazy.notification.billing.application.port.UsageReader;
import com.techeazy.notification.billing.domain.InvoiceCalculator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;

/** The only place billing is wired to Spring: adapters are built here and injected into the framework-free use cases. */
@Configuration
@EnableConfigurationProperties(BillingProperties.class)
public class BillingConfiguration {

    @Bean
    @ConditionalOnMissingBean
    Clock billingClock() {
        return Clock.systemUTC();
    }

    @Bean
    InvoiceCalculator invoiceCalculator() {
        return new InvoiceCalculator();
    }

    @Bean
    PlanRepository planRepository(JdbcClient jdbc) {
        return new JdbcPlanRepository(jdbc);
    }

    @Bean
    AccountRepository accountRepository(JdbcClient jdbc) {
        return new JdbcAccountRepository(jdbc);
    }

    @Bean
    InvoiceRepository invoiceRepository(JdbcClient jdbc) {
        return new JdbcInvoiceRepository(jdbc);
    }

    @Bean
    CreditStore creditStore(JdbcClient jdbc) {
        return new JdbcCreditStore(jdbc);
    }

    @Bean
    OtpPriceRepository otpPriceRepository(JdbcClient jdbc) {
        return new JdbcOtpPriceRepository(jdbc);
    }

    @Bean
    UsageReader usageReader(JdbcClient jdbc) {
        return new JdbcUsageReader(jdbc);
    }

    @Bean
    Transactions billingTransactions(PlatformTransactionManager manager) {
        return new SpringTransactions(new TransactionTemplate(manager));
    }

    @Bean
    PlanCatalog planCatalog(PlanRepository plans, AccountRepository accounts) {
        return new PlanCatalog(plans, accounts);
    }

    @Bean
    AccountManagement accountManagement(AccountRepository accounts, PlanRepository plans, CreditStore credits,
                                        OtpPriceRepository otpPrices, Transactions transactions) {
        return new AccountManagement(accounts, plans, credits, otpPrices, transactions);
    }

    @Bean
    CreditService creditService(AccountRepository accounts, PlanRepository plans, CreditStore credits,
                                Transactions transactions, Clock clock) {
        return new CreditService(accounts, plans, credits, transactions, clock);
    }

    @Bean
    @SuppressWarnings("java:S107")
    AdmissionControl admissionControl(AccountRepository accounts, PlanRepository plans, OtpPriceRepository otpPrices,
                                      CreditStore credits, UsageReader usage, InvoiceCalculator calculator,
                                      Clock clock, @Value("${billing.admission-cache-seconds:5}") long cacheSeconds) {
        Duration ttl = Duration.ofSeconds(Math.max(cacheSeconds, 1));
        AccountLookup accountLookup = cacheSeconds > 0 ? CachedLookups.accounts(accounts, ttl) : accounts;
        PlanLookup planLookup = cacheSeconds > 0 ? CachedLookups.plans(plans, ttl) : plans;
        OtpPriceLookup otpPriceLookup = cacheSeconds > 0 ? CachedLookups.otpPrices(otpPrices, ttl) : otpPrices;
        return new AdmissionControl(accountLookup, planLookup, otpPriceLookup, credits, usage, calculator, clock);
    }

    @Bean
    HoldSettlement holdSettlement(CreditStore credits, Transactions transactions, Clock clock) {
        return new HoldSettlement(credits, transactions, clock);
    }

    @Bean
    @SuppressWarnings("java:S107")
    InvoiceService invoiceService(InvoiceRepository invoices, AccountRepository accounts, PlanRepository plans,
                                  OtpPriceRepository otpPrices, UsageReader usage, InvoiceCalculator calculator,
                                  Transactions transactions, Clock clock, BillingProperties properties) {
        return new InvoiceService(invoices, accounts, plans, otpPrices, usage, calculator, transactions, clock,
                properties.getPaymentTermsDays());
    }

    @Bean
    UsageService usageService(AccountRepository accounts, PlanRepository plans, OtpPriceRepository otpPrices,
                              UsageReader usage, InvoiceCalculator calculator, Clock clock) {
        return new UsageService(accounts, plans, otpPrices, usage, calculator, clock);
    }
}
