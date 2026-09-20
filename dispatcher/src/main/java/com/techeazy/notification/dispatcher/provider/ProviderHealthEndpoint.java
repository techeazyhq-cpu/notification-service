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

package com.techeazy.notification.dispatcher.provider;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.List;

/** GET /actuator/providerhealth: circuit breaker state per provider, as seen by this dispatcher instance. */
@Component
@Endpoint(id = "providerhealth")
public class ProviderHealthEndpoint {

    private final ProviderRegistry registry;

    public ProviderHealthEndpoint(ProviderRegistry registry) {
        this.registry = registry;
    }

    @ReadOperation
    public List<ProviderRegistry.BreakerStatus> breakers() {
        return registry.breakerStatus();
    }
}
