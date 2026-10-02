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

import com.techeazy.notification.application.ProviderDestinationPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.InetAddress;
import java.util.List;

/**
 * The provider destination policy shared by every service that connects to a provider or stores one: admin-api when
 * a provider is saved, the dispatcher before each send, and client-api before a sender confirmation e-mail.
 */
@Configuration
class ProviderDestinationConfig {

    @Bean
    ProviderDestinationPolicy providerDestinationPolicy(
            @Value("${notification.provider-egress.trusted-hosts:}") List<String> trustedHosts,
            @Value("${notification.provider-egress.other-public-hosts-allowed:true}") boolean otherPublicHostsAllowed) {
        return new ProviderDestinationPolicy(trustedHosts, otherPublicHostsAllowed,
                host -> List.of(InetAddress.getAllByName(host)));
    }
}
