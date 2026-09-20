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

import java.math.BigDecimal;
import java.util.List;

/** Subtotal, tax and total of a set of invoice lines. The single place these are calculated, for invoices and estimates alike. */
public record InvoiceTotals(Money subtotal, Money tax, Money total) {

    public static InvoiceTotals of(String currency, List<InvoiceLine> lines, BigDecimal taxRate) {
        Money subtotal = lines.stream().map(InvoiceLine::amount).reduce(Money.zero(currency), Money::plus);
        Money tax = subtotal.percent(taxRate).rounded();
        return new InvoiceTotals(subtotal, tax, subtotal.plus(tax));
    }
}
