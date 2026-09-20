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

/** A prepaid account cannot cover the estimated cost of a request. Nothing was accepted or charged. */
public class InsufficientCreditException extends BillingException {

    private final Money balance;
    private final Money required;

    public InsufficientCreditException(Money balance, Money required) {
        super("Insufficient credit: balance " + balance.display() + ", required " + required.display());
        this.balance = balance;
        this.required = required;
    }

    public Money balance() {
        return balance;
    }

    public Money required() {
        return required;
    }
}
