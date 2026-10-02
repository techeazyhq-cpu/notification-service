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
package com.techeazy.notification.port;

/**
 * A publish that was not attempted because the broker's circuit is open (ADR-032): it failed recently, so the message
 * stays PENDING and the outbox sweeper publishes it once the broker answers again.
 */
public class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException() {
        super("The message broker is unavailable; the message stays PENDING and the outbox sweeper publishes it",
                null, false, false);
    }
}
