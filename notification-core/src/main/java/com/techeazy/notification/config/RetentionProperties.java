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

package com.techeazy.notification.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How long personal data is kept. A value of 0 or less switches that step off. Every service reads the same
 * settings, so the client API can tell clients the policy that the dispatcher actually enforces.
 */
@Getter @Setter
@ConfigurationProperties(prefix = "retention")
public class RetentionProperties {

    /** Run the scheduled job (only the dispatcher does). */
    private boolean enabled = false;
    /** Cron for the daily run, in the server time zone. */
    private String cron = "0 30 2 * * *";
    /** Recipient, template variables and error text of a finished message are erased after this many days. */
    private int personalDataDays = 90;
    /** The message rows themselves (status and counts, no personal data) are deleted after this many days. */
    private int deleteDays = 400;
    /** Idempotency keys stop protecting against repeats after this many days. */
    private int idempotencyDays = 7;
    /** Rows changed per statement; keeps each transaction short. */
    private int batchSize = 5000;
}
