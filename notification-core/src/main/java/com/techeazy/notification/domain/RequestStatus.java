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

/** Derived from the statuses of the messages in a request; never stored. */
public enum RequestStatus {
    PROCESSING, COMPLETED, PARTIALLY_FAILED, FAILED;

    public static RequestStatus derive(long total, long inFlight, long sent, long failed) {
        if (inFlight > 0 || sent + failed < total) return PROCESSING;
        if (failed == 0) return COMPLETED;
        if (sent == 0) return FAILED;
        return PARTIALLY_FAILED;
    }
}
