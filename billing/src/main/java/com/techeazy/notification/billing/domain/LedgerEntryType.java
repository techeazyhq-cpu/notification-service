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

public enum LedgerEntryType {
    /** Credit added after a payment. Positive. */
    TOP_UP,
    /** Credit reserved for accepted messages. Negative. */
    HOLD,
    /** Unused part of a hold returned after the messages finished. Positive. */
    SETTLEMENT,
    /** Manual correction by an administrator. Positive or negative. */
    ADJUSTMENT
}
