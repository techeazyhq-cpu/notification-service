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
package com.techeazy.notification.error;

/**
 * Groups error codes by who has to act. The digit after {@code NS-} in every error id is the category's range, so an
 * error id alone already says where to look: NS-1xxx is the caller's request, NS-8xxx a dependency of ours.
 */
public enum ErrorCategory {
    REQUEST('1', "Request", "The request itself is malformed or incomplete; the caller fixes it."),
    ACCESS('2', "Access", "Authentication or permission was refused; the caller fixes credentials or rights."),
    RESOURCE('3', "Resource", "The resource is missing or in a state that does not allow the action."),
    BILLING('4', "Billing", "The client's billing account does not allow the send; the account owner acts."),
    CAPACITY('5', "Capacity", "A rate limit or capacity limit applies; waiting and retrying succeeds."),
    DELIVERY('6', "Delivery", "A message was accepted but could not be delivered; reported on the message."),
    DEPENDENCY('8', "Dependency", "A system the service relies on is unavailable; the platform team acts."),
    PLATFORM('9', "Platform", "An unexpected fault in the service; the platform team investigates.");

    private final char rangeDigit;
    private final String label;
    private final String meaning;

    ErrorCategory(char rangeDigit, String label, String meaning) {
        this.rangeDigit = rangeDigit;
        this.label = label;
        this.meaning = meaning;
    }

    /** The first digit of every error id in this category. */
    public char rangeDigit() {
        return rangeDigit;
    }

    public String label() {
        return label;
    }

    public String meaning() {
        return meaning;
    }
}
