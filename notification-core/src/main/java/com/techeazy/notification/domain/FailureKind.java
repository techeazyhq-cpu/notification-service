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

package com.techeazy.notification.domain;

/**
 * Why a message ended FAILED, which decides whether reprocessing is worthwhile.
 * {@code PERMANENT}: the provider or the content rejected it (bad recipient, missing variable), so sending again
 * will fail the same way until something is corrected. {@code EXHAUSTED}: every attempt failed for a temporary
 * reason (provider outage, timeouts). {@code DEAD_LETTERED}: the broker gave up delivering it to a worker.
 */
public enum FailureKind {
    PERMANENT, EXHAUSTED, DEAD_LETTERED;

    /** Worth reprocessing without changing anything first. */
    public boolean retryable() {
        return this != PERMANENT;
    }
}
