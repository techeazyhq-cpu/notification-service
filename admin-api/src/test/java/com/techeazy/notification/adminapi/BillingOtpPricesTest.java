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
package com.techeazy.notification.adminapi;

import com.techeazy.notification.adminapi.BillingAdminController.OtpPriceInput;
import com.techeazy.notification.adminapi.BillingAdminController.OtpPricesInput;
import com.techeazy.notification.adminapi.BillingAdminViews.OtpPriceView;
import com.techeazy.notification.adminapi.BillingAdminViews.OtpPricesView;
import com.techeazy.notification.billing.application.AccountManagement;
import com.techeazy.notification.billing.application.CreditService;
import com.techeazy.notification.billing.application.InvoiceService;
import com.techeazy.notification.billing.application.PlanCatalog;
import com.techeazy.notification.billing.domain.AccountStatus;
import com.techeazy.notification.billing.domain.BillingAccount;
import com.techeazy.notification.billing.domain.BillingMode;
import com.techeazy.notification.billing.domain.InvalidBillingDataException;
import com.techeazy.notification.billing.domain.Money;
import com.techeazy.notification.billing.domain.OtpPrices;
import com.techeazy.notification.billing.domain.Plan;
import com.techeazy.notification.domain.Channel;
import com.techeazy.notification.persistence.ClientRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** An administrator sets what each tenant pays per one-time password (ADR-034). */
class BillingOtpPricesTest {

    private final PlanCatalog plans = mock(PlanCatalog.class);
    private final AccountManagement accounts = mock(AccountManagement.class);
    private final BillingAdminController controller = new BillingAdminController(plans, accounts,
            mock(CreditService.class), mock(InvoiceService.class), mock(ClientRepository.class));
    private final UUID client = UUID.randomUUID();
    private final Plan plan = new Plan(UUID.randomUUID(), "standard", "EUR", Money.zero("EUR"), BigDecimal.ZERO,
            Map.of(), true);

    @BeforeEach
    void setUp() {
        when(accounts.get(client)).thenReturn(new BillingAccount(client, plan.id(), BillingMode.POSTPAID, null,
                Money.zero("EUR"), AccountStatus.ACTIVE, null));
        when(plans.get(plan.id())).thenReturn(plan);
    }

    @Test
    void theWholeSetIsSavedAndReturnedInThePlanCurrency() {
        when(accounts.setOtpPrices(client, Map.of(Channel.SMS, new BigDecimal("0.012"))))
                .thenReturn(new OtpPrices(Map.of(Channel.SMS, Money.of("0.012", "EUR"))));

        OtpPricesView saved = controller.setOtpPrices(client,
                new OtpPricesInput(List.of(new OtpPriceInput(Channel.SMS, new BigDecimal("0.012")))));

        assertThat(saved.currency()).isEqualTo("EUR");
        assertThat(saved.prices()).containsExactly(new OtpPriceView(Channel.SMS, Money.of("0.012", "EUR").unitFormatted()));
    }

    @Test
    void noPricesRemovesThemAll() {
        when(accounts.setOtpPrices(client, Map.of())).thenReturn(OtpPrices.NONE);

        assertThat(controller.setOtpPrices(client, new OtpPricesInput(null)).prices()).isEmpty();
    }

    @Test
    void aChannelGivenTwiceIsRefusedBeforeAnythingIsSaved() {
        OtpPricesInput twice = new OtpPricesInput(List.of(new OtpPriceInput(Channel.SMS, BigDecimal.ONE),
                new OtpPriceInput(Channel.SMS, BigDecimal.TEN)));

        assertThatThrownBy(() -> controller.setOtpPrices(client, twice))
                .isInstanceOf(InvalidBillingDataException.class).hasMessageContaining("SMS");
        verify(accounts, never()).setOtpPrices(any(), any());
    }
}
